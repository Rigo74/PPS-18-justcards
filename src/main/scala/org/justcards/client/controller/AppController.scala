package org.justcards.client.controller

import akka.actor.{Actor, Props}
import org.justcards.client.connection_manager.ConnectionManager
import org.justcards.client.connection_manager.ConnectionManager.{Connected, DetailedErrorOccurred, InitializeConnection, TerminateConnection}
import org.justcards.client.view.{MenuChoice, View, ViewFactory}
import org.justcards.commons._
import org.justcards.commons.AppError._

trait AppController {
  def login(username: String): Unit
  def menuSelection(choice: MenuChoice.Value): Unit
  def createLobby(game: GameId): Unit
  def joinLobby(lobby: LobbyId): Unit
}

object AppController {

  def apply(connectionManager: ConnectionManager, viewFactory: ViewFactory) =
    Props(classOf[AppControllerActor], connectionManager, viewFactory)

  private[this] class AppControllerActor(connectionManager: ConnectionManager, viewFactory: ViewFactory) extends Actor
    with AppController {

    import State._

    private val connectionManagerActor = context.actorOf(connectionManager(self))
    private val view: View = viewFactory(this)
    private var state: State.Value = INIT
    connectionManagerActor ! InitializeConnection

    override def receive: Receive = waitToBeConnected orElse default

    override def login(username: String): Unit = {
      if (state == CONNECTED) {
        changeContext(waitToBeLogged)
        connectionManagerActor ! LogIn(username)
      }
    }

    override def menuSelection(choice: MenuChoice.Value): Unit = {
      state match {
        case LOGGED => choice match {
          case MenuChoice.createLobby =>
            changeContext(waitForAvailableGames)
            connectionManagerActor ! RetrieveAvailableGames()
          case MenuChoice.joinLobby =>
            changeContext(waitForAvailableLobbies)
            connectionManagerActor ! RetrieveAvailableLobbies()
          case _ => view error SELECTION_NOT_AVAILABLE
        }
        case CONNECTED => view chooseNickname()
      }
    }

    override def createLobby(game: GameId): Unit = {
      state match {
        case LOGGED =>
          changeContext(waitForLobbyCreation)
          connectionManagerActor ! CreateLobby(game)
        case CONNECTED => view chooseNickname()
      }
    }

    override def joinLobby(lobby: LobbyId): Unit = {
      state match {
        case LOGGED =>
          changeContext(waitForLobbyJoin)
          connectionManagerActor ! JoinLobby(lobby)
        case CONNECTED => view chooseNickname()
      }
    }

    private def waitToBeConnected: Receive = {
      case Connected =>
        state = State.CONNECTED
        context become default
        view chooseNickname()
      case ErrorOccurred(m) if m == CANNOT_CONNECT.toString =>
        view error CANNOT_CONNECT
    }

    private def waitToBeLogged: Receive = {
      case Logged(_) =>
        state = State.LOGGED
        context become default
        view showMenu()
    }

    private def waitForAvailableGames: Receive = {
      case AvailableGames(games) =>
        context become default
        view showLobbyCreation games
    }

    private def waitForLobbyCreation: Receive = {
      case LobbyCreated(lobby) =>
        state = State.IN_LOBBY
        changeContext(inLobby)
        view lobbyCreated lobby
    }

    private def waitForAvailableLobbies: Receive = {
      case AvailableLobbies(lobbies) =>
        context become default
        view showLobbyJoin lobbies
    }

    private def waitForLobbyJoin: Receive = {
      case LobbyJoined(lobby, members) =>
        state = State.IN_LOBBY
        changeContext(inLobby)
        view lobbyJoined (lobby,members)
    }

    private def inLobby: Receive = {
      case LobbyUpdate(lobby, members) => view lobbyUpdate (lobby,members)
    }

    private def default: Receive = {
      case ErrorOccurred(message) =>
        val error = AppError.values.find(_.toString == message)
        if (error.isDefined) error get match {
          case CONNECTION_LOST =>
            changeContext(waitToBeConnected)
            view error CONNECTION_LOST
            connectionManagerActor ! InitializeConnection
          case MESSAGE_SENDING_FAILED =>
            connectionManagerActor ! TerminateConnection
          case message if Set(USER_ALREADY_PRESENT, USER_NOT_LOGGED) contains message =>
            context become default
            view error message
            view chooseNickname()
          case message if Set(GAME_NOT_EXISTING, LOBBY_NOT_EXISTING, LOBBY_FULL) contains message =>
            context become default
            view error message
          case message => view error message
        }
      case DetailedErrorOccurred(MESSAGE_SENDING_FAILED, message) =>
        connectionManagerActor ! message
      case _ =>
    }

    private def changeContext(newBehaviour: Receive): Unit = {
      context become (newBehaviour orElse default)
    }

    private[this] object State extends Enumeration {
      val INIT, CONNECTED, LOGGED, IN_LOBBY = Value
    }
  }
}