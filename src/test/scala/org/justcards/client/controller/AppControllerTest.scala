package org.justcards.client.controller

import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.TestProbe
import scala.concurrent.duration._
import org.justcards.client.{TestConnectionManager, TestView}
import org.justcards.client.connection_manager.ConnectionManager.{Connected, DetailedErrorOccurred, InitializeConnection, TerminateConnection}
import org.justcards.client.controller.AppController._
import org.justcards.client.view.{MenuChoice, OptionConnectionFailed}
import org.justcards.client.view.View._
import org.justcards.commons.AppError._
import org.justcards.commons._
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

class AppControllerTest() extends WordSpecLike with Matchers with BeforeAndAfterAll {

  private implicit val system: ActorSystem = ActorSystem("AppControllerTest")

  override def afterAll: Unit = {
    system.terminate()
  }

  import org.justcards.client.Utils._

  "The application controller" when {

    "the application starts" should {

      "tell the view to make the user choose a nickname" in {
        val (appController, testProbe) = initComponents
        appController ! Connected
        testProbe expectMsg ShowUsernameChoice
      }

    }

    "a user wants to log in" should {

      "send a LogIn message to the connection manager with the given username" in {
        val (appController, testProbe) = connect
        appController ! ChosenUsername(username)
        testProbe expectMsg LogIn(username)
      }

      "inform the user that was correctly logged in the system" in {
        val (appController, testProbe) = login(hasToBeLogged = false)
        appController ! Logged()
        testProbe expectMsg ShowMenu
      }

      "inform the user if another user with the same name is already present" in {
        val (appController, testProbe) = initComponents
        appController ! ErrorOccurred(USER_ALREADY_PRESENT)
        testProbe expectMsg ShowError(USER_ALREADY_PRESENT)
      }
    }

    "a user wants to the create a lobby" should {

      "send a message to the connection manager to get the available games" in {
        val (appController, testProbe) = login()
        appController ! MenuSelection(MenuChoice.CREATE_LOBBY)
        testProbe.expectMsgType[RetrieveAvailableGames]
      }

      "inform the user about the available games when received" in {
        val (appController, testProbe) = retrieveAvailableGames
        val games = Set(game)
        appController ! AvailableGames(games)
        testProbe expectMsg ShowLobbyCreation(games)
      }

      "send a message to the connection manager to create a lobby, when the user ask for it" in {
        val (appController, testProbe) = retrieveAvailableGames
        appController ! AppControllerCreateLobby(game)
        testProbe expectMsg CreateLobby(game)
      }

      "inform the user that he created a lobby when notified" in {
        val (appController, testProbe) = createLobby
        appController ! LobbyCreated(lobby)
        testProbe expectMsg ShowCreatedLobby(lobby)
      }

    }

    "a user wants to join a lobby" should {

      "send a message to the connection manager to get the available lobbies" in {
        val (appController, testProbe) = login()
        appController ! MenuSelection(MenuChoice.JOIN_LOBBY)
        testProbe.expectMsgType[RetrieveAvailableLobbies]
      }

      "inform the user about the available lobbies when received" in {
        val (appController, testProbe) = retrieveAvailableLobbies
        val lobbies = Set((lobby,Set(user)))
        appController ! AvailableLobbies(lobbies)
        testProbe expectMsg ShowLobbies(lobbies)
      }

      "send a message to the connection manager to join a lobby, when the user ask for it" in {
        val (appController, testProbe) = retrieveAvailableLobbies
        appController ! AppControllerJoinLobby(lobby)
        testProbe expectMsg JoinLobby(lobby)
      }

      "inform the user that he joined a lobby when notified" in {
        val (appController, testProbe) = joinLobby
        val tuple = (lobby, Set(user))
        appController ! LobbyJoined(tuple._1, tuple._2)
        testProbe expectMsg ShowJoinedLobby(tuple._1, tuple._2)
      }

    }

    "a user is in a lobby" should {

      "inform the user if notified of a update regarding the lobby he created" in {
        val (appController, testProbe) = createLobby
        appController ! LobbyCreated(lobby)
        testProbe receiveN 1
        val tuple = (lobby, Set(user))
        appController ! LobbyUpdate(tuple._1, tuple._2)
        testProbe expectMsg ShowLobbyUpdate(tuple._1, tuple._2)
      }

      "inform the user if notified of a update regarding the lobby he joined" in {
        val (appController, testProbe) = joinLobby
        val tuple = (lobby, Set(user))
        appController ! LobbyJoined(tuple._1, tuple._2)
        testProbe receiveN 1
        appController ! LobbyUpdate(tuple._1, tuple._2)
        testProbe expectMsg ShowLobbyUpdate(tuple._1, tuple._2)
      }

      "inform the user that the game is started" in {
        val (appController, testProbe) = createLobby
        appController ! LobbyCreated(lobby)
        testProbe receiveN 1
        appController ! GameStarted(team)
        testProbe expectMsg ShowGameStarted(team)
      }

    }

    "a user has started a game session" should {

      "inform the user each time it receives new information about the game" in {
        val (appController, testProbe) = startGame
        appController ! Information(handCards, fieldCards)
        testProbe expectMsg ShowGameInformation(handCards, fieldCards)
      }

      "inform the user about who won the current hand" in {
        val (appController, testProbe) = startGame
        appController ! HandWinner(user)
        testProbe expectMsg ShowHandWinner(user)
      }

      "inform the user about who won the current match" in {
        val (appController, testProbe) = startGame
        val team1Points = 1
        val team2Points = 2
        appController ! MatchWinner(team, team1Points, team2Points)
        testProbe expectMsg ShowMatchWinner(team, team1Points, team2Points)
      }

      "inform the user about who won the game" in {
        val (appController, testProbe) = startGame
        appController ! GameWinner(team)
        testProbe expectMsg ShowGameWinner(team)
      }

      "ask the user to choose a Briscola when it receives the command from the connectionManager" in {
        val (appController, testProbe) = startGame
        appController ! ChooseBriscola(briscolaTime)
        testProbe expectMsg ViewChooseBriscola(briscolaTime)
      }

      "send a message to the connection manager, when the user chose the Briscola" in {
        val (appController, testProbe) = chooseBriscola
        val briscola = "spade"
        appController ! ChosenBriscola(briscola)
        testProbe expectMsg Briscola(briscola)
      }

      "send a timeout message after a default time if the user doesn't choose a Briscola" in {
        implicit val (_, testProbe) = chooseBriscola
        expectTimeoutExceeded(briscolaTime)
      }

      "not send a timeout message to the connection manager if the user chooses a Briscola before the timeout" in {
        implicit val (appController, testProbe) = chooseBriscola
        expectNoTimeoutExceeded(appController, ChosenBriscola("spade"), briscolaTime)
      }

      "ask again to the user to choose the Briscola if the choice was incorrect" in {
        implicit val (appController, testProbe) = chooseBriscola
        appController ! ChosenBriscola("spade")
        testProbe receiveN 1
        appController ! ErrorOccurred(BRISCOLA_NOT_VALID)
        testProbe expectMsgAllOf(ShowError(BRISCOLA_NOT_VALID), ViewChooseBriscola(briscolaTime))
      }

      "send a timeout message after the correct time if the user chosen the wrong Briscola" in {
        implicit val (appController, testProbe) = chooseBriscola
        sendErrorAndExpectTimeoutExceeded(appController, briscolaTime)(ChosenBriscola("spade"), BRISCOLA_NOT_VALID)
      }

      "tell the user that is his turn after receiving a Turn message from connection manager" in {
        val (appController, testProbe) = startGame
        appController ! Turn(handCards, fieldCards, turnTime)
        testProbe expectMsg ShowTurn(handCards, fieldCards, turnTime)
      }

      "send the card the user wants to play to the connection manager" in {
        val (appController, testProbe) = myTurn
        appController ! ChosenCard(card)
        testProbe expectMsg Play(card)
      }

      "send a timeout message after a default time if the user doesn't play a card" in {
        implicit val (_, testProbe) = myTurn
        expectTimeoutExceeded(turnTime)
      }

      "not send a timeout message to the connection manager if the user plays a card before the timeout" in {
        implicit val (appController, testProbe) = myTurn
        expectNoTimeoutExceeded(appController, ChosenCard(card), turnTime)
      }

      "ask again to the user to play a card if the choice was incorrect" in {
        val (appController, testProbe) = myTurn
        appController ! ChosenCard(card)
        testProbe receiveN 1
        appController ! ErrorOccurred(CARD_NOT_VALID)
        testProbe expectMsgAllOf(ShowError(CARD_NOT_VALID), ShowTurn(handCards, fieldCards, turnTime))
      }

      "send a timeout message after the correct time if the user played the wrong card" in {
        implicit val (appController, testProbe) = myTurn
        sendErrorAndExpectTimeoutExceeded(appController, turnTime)(ChosenCard(card), CARD_NOT_VALID)
      }

      "end game session and return to the menu when the session is over" in {
        val (appController, testProbe) = startGame
        appController ! OutOfLobby(lobby)
        testProbe expectMsg ShowMenu
      }

    }

    "a connection error occurs" should {

      "inform the user that the system is not available and the application won't work" in {
        val (appController, testProbe) = initComponents
        appController ! ErrorOccurred(CANNOT_CONNECT)
        testProbe expectMsg ShowError(CANNOT_CONNECT)
      }

      "try to reconnect if the user asks to do it" in {
        val (appController, testProbe) = initComponents
        appController ! ErrorOccurred(CANNOT_CONNECT)
        testProbe receiveN 1
        appController ! ReconnectOption(OptionConnectionFailed.TRY_TO_RECONNECT)
        testProbe expectMsg InitializeConnection
      }

      "try to reconnect to the server and inform the user that the connection was lost" in {
        val (appController, testProbe) = initComponents
        appController ! ErrorOccurred(CONNECTION_LOST)
        testProbe expectMsgAllOf (ShowError(CONNECTION_LOST), InitializeConnection)
      }

      "if a message was not correctly delivered, without know the message, destroy the connection and notify the user of connection lost" in {
        val (appController, testProbe) = initComponents
        appController ! ErrorOccurred(MESSAGE_SENDING_FAILED)
        testProbe expectMsg TerminateConnection
      }

      "if a message was not correctly delivered, knowing the message, try to send the message another time" in {
        val (appController, testProbe) = initComponents
        val msg = LogIn(username)
        appController ! DetailedErrorOccurred(MESSAGE_SENDING_FAILED, msg)
        testProbe expectMsg msg
      }

    }

  }

  private def initComponents:(ActorRef, TestProbe) = {
    val testProbe = TestProbe()
    val testActor: ActorRef = testProbe.ref
    val appController = system.actorOf(AppController(TestConnectionManager(testActor),TestView(testActor)))
    testProbe receiveN 1
    (appController, testProbe)
  }

  private def connect: (ActorRef, TestProbe) = {
    val (appController, testProbe) = initComponents
    appController ! Connected
    testProbe receiveN 1
    (appController, testProbe)
  }

  private def login(hasToBeLogged: Boolean = true):(ActorRef, TestProbe) = {
    val (appController, testProbe) = connect
    appController ! ChosenUsername(username)
    if(hasToBeLogged) {
      appController ! Logged()
      testProbe receiveN 2
    } else
      testProbe receiveN 1
    (appController, testProbe)
  }

  private def retrieveAvailableGames:(ActorRef, TestProbe) = {
    val (appController, testProbe) = login()
    appController ! MenuSelection(MenuChoice.CREATE_LOBBY)
    testProbe receiveN 1
    (appController, testProbe)
  }

  private def createLobby:(ActorRef, TestProbe) = {
    val (appController, testProbe) = retrieveAvailableGames
    appController ! AppControllerCreateLobby(game)
    testProbe receiveN 1
    (appController, testProbe)
  }

  private def retrieveAvailableLobbies:(ActorRef, TestProbe) = {
    val (appController, testProbe) = login()
    appController ! MenuSelection(MenuChoice.JOIN_LOBBY)
    testProbe receiveN 1
    (appController, testProbe)
  }

  private def joinLobby: (ActorRef, TestProbe) = {
    val (appController, testProbe) = retrieveAvailableLobbies
    appController ! AppControllerJoinLobby(lobby)
    testProbe receiveN 1
    (appController, testProbe)
  }

  private def startGame: (ActorRef, TestProbe) = {
    val (appController, testProbe) = createLobby
    appController ! LobbyCreated(lobby)
    appController ! GameStarted(team)
    testProbe receiveN 2
    (appController, testProbe)
  }

  private def chooseBriscola: (ActorRef, TestProbe) = {
    val (appController, testProbe) = startGame
    appController ! ChooseBriscola(briscolaTime)
    testProbe receiveN 1
    (appController, testProbe)
  }

  private def myTurn: (ActorRef, TestProbe) = {
    val (appController, testProbe) = startGame
    appController ! Turn(handCards, fieldCards, turnTime)
    testProbe receiveN 1
    (appController, testProbe)
  }

  private def expectTimeoutExceeded(timeLimit: FiniteDuration)(implicit testProbe: TestProbe): Unit = {
    testProbe.within(timeLimit + 1.second){
      testProbe.expectMsgAllOf(TimeoutExceeded(), ShowTimeForMoveExceeded)
    }
  }

  private def expectNoTimeoutExceeded(appController: ActorRef, msgToSend: Any, timeLimit: FiniteDuration)
                                  (implicit testProbe: TestProbe): Unit = {
    testProbe.within(briscolaTime + 1.second){
      Thread.sleep(1000)
      appController ! msgToSend
      testProbe receiveN 1
      testProbe expectNoMessage
    }
  }

  private def sendErrorAndExpectTimeoutExceeded(appController: ActorRef, timeLimit: FiniteDuration)
                                               (msgToSend: Any, errorToSend: AppError.Value)
                                               (implicit testProbe: TestProbe): Unit = {
    Thread.sleep(timeLimit.toMillis/2)
    appController ! msgToSend
    testProbe receiveN 1
    appController ! ErrorOccurred(errorToSend)
    testProbe receiveN 2
    expectTimeoutExceeded(FiniteDuration(timeLimit._1/2, timeLimit._2))
  }

}
