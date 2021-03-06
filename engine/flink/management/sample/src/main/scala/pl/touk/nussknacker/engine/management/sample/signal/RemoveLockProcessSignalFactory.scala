package pl.touk.nussknacker.engine.management.sample.signal

import java.lang
import java.nio.charset.StandardCharsets

import argonaut.Argonaut._
import argonaut.ArgonautShapeless._
import argonaut.{Argonaut, Json}
import com.typesafe.scalalogging.LazyLogging
import org.apache.flink.api.common.state.{ValueState, ValueStateDescriptor}
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.streaming.api.operators.{AbstractStreamOperator, OneInputStreamOperator, TwoInputStreamOperator}
import org.apache.flink.streaming.api.scala.{DataStream, _}
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord
import pl.touk.nussknacker.engine.api.signal.SignalTransformer
import pl.touk.nussknacker.engine.api.{MethodToInvoke, ParamName, _}
import pl.touk.nussknacker.engine.flink.api.process.{FlinkCustomNodeContext, FlinkCustomStreamTransformation}
import pl.touk.nussknacker.engine.flink.api.signal.FlinkProcessSignalSender
import pl.touk.nussknacker.engine.flink.util.signal.KafkaSignalStreamConnector
import pl.touk.nussknacker.engine.kafka.{KafkaConfig, KafkaEspUtils}


class RemoveLockProcessSignalFactory(val kafkaConfig: KafkaConfig, val signalsTopic: String)
  extends FlinkProcessSignalSender with KafkaSignalStreamConnector {

  import Signals._

  @MethodToInvoke
  def sendSignal(@ParamName("lockId") lockId: String)(processId: String): Unit = {
    val signal = SampleProcessSignal(processId, System.currentTimeMillis(), RemoveLock(lockId))
    val json = ProcessSignalCodecs.processSignalCodec.Encoder(signal).nospaces
    KafkaEspUtils.sendToKafkaWithTempProducer(signalsTopic, Array.empty, json.getBytes(StandardCharsets.UTF_8))(kafkaConfig)
  }

}

object SampleSignalHandlingTransformer {
  import Signals._

  trait SignalHandler extends LazyLogging {
    val metaData: MetaData

    def handleIfSignalForThisProcess(signal: SampleProcessSignal)(handle: SampleProcessSignal => Unit): Unit = {
      if (metaData.id == signal.processId) {
        logger.info(s"Signal received: $signal")
        handle(signal)
      } else {
        logger.info(s"Signal for other process received, ignoring. Current process ${metaData.id}, signal $signal")
      }
    }
  }

  class LockStreamTransformer extends CustomStreamTransformer {
    final val lockQueryName = "locks-state" //it has to be `final` to use it as value in Java annoatation, otherwise it won't compile

    @SignalTransformer(signalClass = classOf[RemoveLockProcessSignalFactory])
    @QueryableStateNames(values = Array(lockQueryName))
    @MethodToInvoke(returnType = classOf[LockOutput])
    def execute(@ParamName("input") input: LazyParameter[String]) =
      FlinkCustomStreamTransformation((start: DataStream[Context], context: FlinkCustomNodeContext) => {
        val ds = context.signalSenderProvider.get[RemoveLockProcessSignalFactory].connectWithSignals(start.map(context.lazyParameterHelper.lazyMapFunction(input)),
          context.metaData.id, context.nodeId, SignalSchema.deserializationSchema)
          .keyBy(_.value, _.action.key)
          .transform("lockStreamTransform", new LockStreamFunction(context.metaData))
        ds
          .keyBy(_ => QueryableState.defaultKey)
          .transform("queryableStateTransform", new MakeStateQueryableTransformer[LockOutputStateChanged, LockOutput](lockQueryName, lockOutput => Argonaut.jObjectFields(
            "lockEnabled" -> jBool(lockOutput.lockEnabled)
          )){}.asInstanceOf[OneInputStreamOperator[Either[LockOutputStateChanged, ValueWithContext[LockOutput]], ValueWithContext[Any]]])
      })
  }


  class LockStreamFunction(val metaData: MetaData)
    extends AbstractStreamOperator[Either[LockOutputStateChanged, ValueWithContext[LockOutput]]] with TwoInputStreamOperator[ValueWithContext[String], SampleProcessSignal, Either[LockOutputStateChanged, ValueWithContext[LockOutput]]]
      with LazyLogging with SignalHandler {

    var lockEnabledState: ValueState[java.lang.Boolean] = _

    override def open(): Unit = {
      super.open()
      val descriptor = new ValueStateDescriptor[lang.Boolean]("lockEnabled", classOf[lang.Boolean])
      descriptor.setQueryable("single-lock-state")
      lockEnabledState = getRuntimeContext.getState(descriptor)
    }

    override def processElement1(element: StreamRecord[ValueWithContext[String]]): Unit = {
      setInitialStateIfStateNotDefined()
      output.collect(new StreamRecord[Either[LockOutputStateChanged, ValueWithContext[LockOutput]]](
        Right(ValueWithContext(LockOutput(lockEnabled = lockEnabledState.value()), element.getValue.context)), element.getTimestamp)
      )
    }

    override def processElement2(element: StreamRecord[SampleProcessSignal]): Unit = {
      handleIfSignalForThisProcess(element.getValue) { signal =>
        signal.action match {
          case _: RemoveLock =>
            changeState(false)
            logger.info(s"Lock successfully removed $signal")
        }
      }
    }

    private def setInitialStateIfStateNotDefined() = {
      logger.info("Setting lock state to true")
      if (lockEnabledState.value() == null) {
        changeState(true)
      }
    }

    def changeState(newValue: Boolean) = {
      if (lockEnabledState.value() != newValue) {
        logger.info(s"Setting lock state to $newValue")
        lockEnabledState.update(newValue)
        output.collect(new StreamRecord[Either[LockOutputStateChanged, ValueWithContext[LockOutput]]](
          Left(LockOutputStateChanged(key = getCurrentKey.toString, lockEnabled = lockEnabledState.value(), changedTimestamp = System.currentTimeMillis())))
        )
      }
    }
  }

  case class LockOutput(lockEnabled: Boolean)
  case class LockOutputStateChanged(key: String, lockEnabled: Boolean, changedTimestamp: Long) extends ChangedState

  abstract class MakeStateQueryableTransformer[A <: ChangedState, B](queryName: String, mapToJson: A => Json) extends
    AbstractStreamOperator[ValueWithContext[B]] with OneInputStreamOperator[Either[A, ValueWithContext[B]], ValueWithContext[B]] with LazyLogging {

    case class QueriedState(key: String, jsonValue: Json, changeTimestamp: Long)

    var queriedStates: ValueState[String] = _

    override def open(): Unit = {
      super.open()
      val queriedStateDescriptor = new ValueStateDescriptor("queriedStates", implicitly[TypeInformation[String]]) //this TypeInformation has to be matched by the one used during quering state
      queriedStateDescriptor.setQueryable(queryName)
      queriedStates = getRuntimeContext.getState(queriedStateDescriptor)
    }

    override def processElement(element: StreamRecord[Either[A, ValueWithContext[B]]]): Unit = {
      setInitialStateIfNoSet()
      element.getValue match {
        case Left(changedValue) =>
          val stateListJson = queriedStates.value().decodeOption[List[QueriedState]].get
          val newValue = QueriedState(key = changedValue.key, jsonValue = mapToJson(changedValue), changeTimestamp = changedValue.changedTimestamp)
          val newState = stateListJson.filter(_.key != changedValue.key) ++ List(newValue)
          queriedStates.update(newState.asJson.nospaces)
        case Right(value) =>
          output.collect(new StreamRecord(value, element.getTimestamp))
      }
    }

    private def setInitialStateIfNoSet() = {
      if (queriedStates.value() == null) {
        queriedStates.update(List.empty[QueriedState].asJson.nospaces)
      }
    }
  }

  trait ChangedState {
    val key: String
    val changedTimestamp: Long
  }
}