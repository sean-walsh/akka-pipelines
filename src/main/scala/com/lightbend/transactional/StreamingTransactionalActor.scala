//package com.lightbend.transactional
//
//import akka.actor.{ActorLogging, ActorNotFound, ActorSelection, ReceiveTimeout, Timers}
//import akka.persistence.{PersistentActor, RecoveryCompleted}
//import akka.util.Timeout
//import com.lightbend.transactional.PersistentSagaActorCommands.{AddTransactionCommand, PersistentTransactionCommand}
//
//import scala.concurrent.ExecutionContext
//import scala.concurrent.duration._
//
//object PersistentTransactionalActor {
//
//  case class StartStreamingTransaction(transactionId: String, description: String, initialCommand: AddTransactionCommand)
//    extends StartTransactionCommand
//  case class AddTransactionCommand(transactionId: String, command: TransactionalCommand, sequence: Long)
//    extends PersistentTransactionCommand
//  case class EndStreamingTransaction(transactionId: String) extends PersistentTransactionCommand
//case class StreamingSagaStarted(transactionId: String, description: String, nodeEventTag: String) extends SagaEvent {
//  override val entityId = transactionId
//}
//case class SagaCommandAdded(transactionId: String, command: TransactionalCommand, sequence: Long) extends SagaEvent {
//  override val entityId = transactionId
//}
//case class StreamingSagaEnded(transactionId: String)
//}
//
///**
//  * This is effectively a long lived transaction that operates within an Akka cluster. Classic saga patterns
//  * will be followed, such as retrying rollback over and over as well as retry of transactions over and over if
//  * necessary, before rollback.
//  *
//  * All persisted transactions will be tagged with the unique-per-node nodeEventTag.
//  */
//class PersistentTransactionalActor(nodeEventTag: String) extends Timers with PersistentActor with ActorLogging {
//
//  import PersistentSagaActorCommands._
//  import PersistentSagaActorEvents._
//  import PersistentTransactionalActor._
//
//  implicit def ec: ExecutionContext = context.system.dispatcher
//
//  override def persistenceId: String = self.path.name
//
//  /**
//    * How often to retry transaction subscription confirmations missing after a wait time. The retry-after setting
//    * should be coarse enough to allow the same timer to be used across pending, committing and rollingBack.
//    */
//  private case object Retry
//
//  private case object TimerKey
//
//  private val retryAfter: FiniteDuration =
//    context.system.settings.config.getDuration("akka-saga.bank-account.saga.retry-after").toNanos.nanos
//
//  context.setReceiveTimeout(10.seconds)
//
//  final private val Uninitialized = "uninitialized"
//  final private val Pending = "pending"
//  final private val Committing = "committing"
//  final private val RollingBack = "rollingBack"
//
//  private var state: SagaState = SagaState("", "", Uninitialized, "")
//
//  override def receiveCommand: Receive = uninitialized
//
//  /**
//    * In this state we are hobbled until we are sent the start message. Instantiation of this actor has to be in two
//    * steps since the edge, in this case the restful route, must assign the transactionId, which automatically
//    * becomes the persistentId. Since cluster sharding only allows construction with objects known when the app
//    * starts, we have to send the commands as a second step.
//    */
//  private def uninitialized: Receive = {
//    case StartTransaction(transactionId, description, commands) =>
//      persist(SagaTransactionStarted(transactionId, description, nodeEventTag, commands)) { started =>
//        applySagaStarted(started)
//        applySagaStartedSideEffects(started)
//        sender() ! Ack
//      }
//    case StartStreamingSaga(transactionId, description, add) =>
//      import collection.immutable._
//      persistAll(Seq(
//        StreamingSagaStarted(transactionId, description, nodeEventTag),
//        SagaCommandAdded(transactionId, add.command, add.sequence))) {
//          case started: StreamingSagaStarted =>
//            sender() ! Ack
//            applyStreamingSagaStarted(started)
//            applyStreamingSagaStartedSideEffects(started)
//          case added: SagaCommandAdded =>
//            applySagaCommandAdded(added)
//            applySagaCommandAddedSideEffects(added)
//      }
//    case ReceiveTimeout =>
//      log.error(s"Aborting transaction ${self.path.name} never received StartSaga command.")
//      context.stop(self)
//    case GetSagaState =>
//      sender() ! state
//  }
//
//  /**
//    * The pending state. No commit OR rollback will occur until all pending events are in place, as per a Saga.
//    * Here we receive event subscription messages applicable to "pending".
//    */
//  private def pending: Receive = {
//    case started@TransactionStarted(_, entityId, _, _) =>
//      started.event match {
//        case _: TransactionalExceptionEvent =>
//          if (!state.exceptions.exists(_.entityId == entityId)) {
//            persist(started) { event =>
//              sender() ! Ack
//              applyTransactionStarted(event)
//              applyTransactionStartedEventSideEffects(started)
//            }
//          }
//        case _ =>
//          if (!state.pendingConfirmed.contains(entityId)) {
//            persist(started) { event =>
//              sender() ! Ack
//              applyTransactionStarted(event)
//              applyTransactionStartedEventSideEffects(started)
//            }
//          }
//      }
//    case Retry =>
//      log.info(s"retrying commands for transactionId: ${state.transactionId}")
//      state.commands.diff(state.pendingConfirmed).foreach( c =>
//        getShardRegion(c.entityId) ! c
//      )
//    case AddTransactionCommand(transactionId, command, sequence) =>
//      if (sequence - state.streamingSequenceNum == 1)
//        persist(SagaCommandAdded(transactionId, command, sequence)) { added =>
//          sender() ! Ack
//          applySagaCommandAdded(added)
//          applySagaCommandAddedSideEffects(added)
//        }
//      else {
//        log.info(s"ending transaction ${state.transactionId} due to command out of sequence.")
//      }
//    case EndStreamingSaga(transactionId) =>
//      persist(StreamingSagaEnded(transactionId)) { _ =>
//        applyStreamingSagaEnded()
//      }
//      sender() ! Ack
//    case GetSagaState =>
//      sender() ! state
//  }
//
//  /**
//    * The committing state. When in this state we can only repeatedly attempt to commit. This transaction will remain
//    * alive until commits have occurred across the board.
//    * Here we receive messages from the entities applicable to "committing".
//    */
//  private def committing: Receive = {
//    case cleared@TransactionCleared(_, entityId, _) =>
//      if (!state.commitConfirmed.contains(entityId)) {
//        persist(cleared) { event =>
//          sender() ! Ack
//          applyTransactionCleared(event)
//        }
//      }
//    case Retry =>
//      log.info(s"retrying commands for transactionId: ${state.transactionId}")
//      state.commands.diff(state.pendingConfirmed).foreach( c =>
//        getShardRegion(c.entityId) ! c
//      )
//    case GetSagaState =>
//      sender() ! state
//  }
//
//  /**
//    * The rolling back state. When in this state we can only repeatedly attempt to rollback. This transaction will remain
//    * alive until rollbacks have occurred across the board.
//    * Here we receive event subscription messages applicable to "rollingBack".
//    */
//  private def rollingBack: Receive = {
//    case reversed@TransactionReversed(_, entityId, _) =>
//      if (!state.rollbackConfirmed.contains(entityId)) {
//        persist(reversed) { event =>
//          sender() ! Ack
//          applyTransactionReversed(event)
//        }
//      }
//    case GetSagaState =>
//      sender() ! state
//  }
//
//  private def applySagaStarted(started: SagaTransactionStarted): Unit = {
//    state = SagaState(started.transactionId, started.description, Pending, started.nodeEventTag, false, false, 0L, started.commands)
//    context.become(pending)
//  }
//
//  private def applySagaStartedSideEffects(started: SagaTransactionStarted): Unit = {
//    log.info(s"starting new saga with transactionId: ${started.transactionId}")
//
//    started.commands.foreach( cmd =>
//      getShardRegion(cmd.shardRegion) ! StartEntityTransaction(state.transactionId, cmd.entityId, state.originalEventTag, cmd)
//    )
//
//    conditionallySpinUpEventSubscriber(state.originalEventTag)
//    timers.startPeriodicTimer(TimerKey, Retry, retryAfter)
//  }
//
//  private def applyStreamingSagaStarted(started: StreamingSagaStarted): Unit = {
//    state = SagaState(started.transactionId, started.description, Pending, started.nodeEventTag, true)
//    context.become(pending)
//  }
//
//  private def applyStreamingSagaStartedSideEffects(started: StreamingSagaStarted): Unit = {
//    log.info(s"starting new saga with transactionId: ${started.transactionId}")
//    conditionallySpinUpEventSubscriber(state.originalEventTag)
//    timers.startPeriodicTimer(TimerKey, Retry, retryAfter)
//  }
//
//  private def applySagaCommandAdded(added: SagaCommandAdded): Unit = {
//    state.copy(commands = state.commands :+ added.command, streamingSequenceNum = added.sequence)
//  }
//
//  private def applySagaCommandAddedSideEffects(added: SagaCommandAdded): Unit =
//    getShardRegion(added.command.shardRegion) ! StartEntityTransaction(state.transactionId, added.command.entityId, state.originalEventTag, added.command)
//
//  private def applyStreamingSagaEnded(): Unit = {
//    state = state.copy(streamingSagaEnded = true)
//
//    if (commitCondition()) {
//      state = state.copy(currentState = Committing)
//      context.become(committing)
//    }
//    else if (rollbackCondition()) {
//      state = state.copy(currentState = RollingBack)
//      context.become(rollingBack)
//    }
//  }
//
//  private def applyTransactionStarted(started: TransactionStarted): Unit = {
//    started.event match {
//      case _: TransactionalExceptionEvent =>
//        state = state.copy(exceptions = (state.exceptions :+ started).sortWith(_.entityId < _.entityId))
//      case _ =>
//        state = state.copy(pendingConfirmed = (state.pendingConfirmed :+ started.entityId).sortWith(_ < _))
//    }
//
//    if (commitCondition()) {
//      state = state.copy(currentState = Committing)
//      context.become(committing)
//    }
//    else if (rollbackCondition()) {
//      state = state.copy(currentState = RollingBack)
//      context.become(rollingBack)
//    }
//  }
//
//  private def applyTransactionStartedEventSideEffects(started: TransactionStarted): Unit = {
//    started.event match {
//      case _: TransactionalExceptionEvent =>
//        log.info(s"Transaction rolling back when possible due to exception on account ${started.entityId}.")
//      case _ =>
//    }
//
//    if (state.currentState == Committing) {
//      state.commands.foreach( cmd =>
//        getShardRegion(cmd.shardRegion) ! CommitTransaction(state.transactionId, cmd.entityId, state.originalEventTag)
//      )
//    }
//    else if (state.currentState == RollingBack) {
//      state.pendingConfirmed.foreach( entityId =>
//        getShardRegion(state.commands.find(_.entityId == entityId).get.shardRegion) ! RollbackTransaction(
//          state.transactionId, entityId, state.originalEventTag)
//      )
//    }
//  }
//
//  private def applyTransactionCleared(event: TransactionCleared): Unit = {
//    state = state.copy(commitConfirmed = (state.commitConfirmed :+ event.entityId).sortWith(_ < _))
//
//    if (completionCondition()) {
//      persist(SagaTransactionComplete(state.transactionId)) { _ =>
//        log.info(s"Saga completed successfully for transactionId: ${state.transactionId}")
//        timers.cancel(TimerKey)
//        context.stop(self)
//      }
//    }
//    else {
//      state = state.copy(currentState = Committing)
//      context.become(committing)
//    }
//  }
//
//  private def applyTransactionReversed(event: TransactionReversed): Unit = {
//    state = state.copy(rollbackConfirmed = (state.rollbackConfirmed :+ event.entityId).sortWith(_ < _))
//
//    if (completionCondition()) {
//      persist(SagaTransactionComplete(state.transactionId)) { _ =>
//        log.info(s"Saga completed with rollback for transactionId: ${state.transactionId}")
//        timers.cancel(TimerKey)
//        context.stop(self)
//      }
//    }
//  }
//
//  // Checks and conditionally moves to rollback.
//  private def commitCondition(): Boolean =
//    if (state.pendingConfirmed.size == state.commands.size && state.exceptions.isEmpty && !state.streamingSaga)
//      true
//    else if (state.pendingConfirmed.size == state.commands.size && state.exceptions.isEmpty && state.streamingSaga
//      && state.streamingSagaEnded)
//      true
//    else
//      false
//
//  // Checks for rollback condition.
//  private def rollbackCondition(): Boolean =
//    if (!state.exceptions.isEmpty && state.commands.size == state.pendingConfirmed.size + state.exceptions.size)
//      true
//    else
//      false
//
//  // Checks for completion condition.
//  private def completionCondition(): Boolean = {
//    if (state.currentState == Committing && state.commitConfirmed.size == state.commands.size)
//      true
//    else if (state.currentState == RollingBack && state.rollbackConfirmed.size == state.commands.size - state.exceptions.size)
//      true
//    else
//      false
//  }
//
//  /**
//    * In the case that this saga has restarted on or been moved to another node, will ensure that there is an event
//    * subscriber for the original eventTag.
//    */
//  private def conditionallySpinUpEventSubscriber(originalEventTag: String): Unit = {
//    if (originalEventTag != nodeEventTag) {
//      // Spin up my own event subscriber, unless one already exists.
//      implicit val timeout = Timeout(10.seconds)
//
//      context.actorSelection(s"${TaggedEventSubscription.ActorNamePrefix}/$originalEventTag")
//        .resolveOne().recover {
//        case ActorNotFound(_) => context.system.actorOf(TransientTaggedEventSubscription.props(nodeEventTag),
//          s"${TaggedEventSubscription.ActorNamePrefix}/$nodeEventTag")
//      }
//    }
//  }
//
//  // Derive entity's shard region ActorSelection.
//  private def getShardRegion(regionName: String): ActorSelection =
//    context.actorSelection(s"/user/$regionName")
//
//  final override def receiveRecover: Receive = {
//    case started: SagaTransactionStarted =>
//      applySagaStarted(started)
//    case started: TransactionStarted =>
//      applyTransactionStarted(started)
//    case streaming: StreamingSagaStarted =>
//      applyStreamingSagaStarted(streaming)
//    case added: SagaCommandAdded =>
//      applySagaCommandAdded(added)
//    case cleared: TransactionCleared =>
//      applyTransactionCleared(cleared)
//    case reversed: TransactionReversed =>
//      applyTransactionReversed(reversed)
//    case _: StreamingSagaEnded =>
//      applyStreamingSagaEnded()
//    case _: SagaTransactionComplete =>
//      context.stop(self)
//    case RecoveryCompleted =>
//      if (List(Pending, Committing, RollingBack).contains(state.currentState))
//        conditionallySpinUpEventSubscriber(state.originalEventTag)
//  }
//}