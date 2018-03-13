package pl.touk.nussknacker.engine.process.functional

import java.util.Date

import pl.touk.nussknacker.engine.api.typed.ClazzRef
import org.scalatest.{FlatSpec, Matchers}
import pl.touk.nussknacker.engine.api.{MetaData, StreamMetaData}
import pl.touk.nussknacker.engine.build.EspProcessBuilder
import pl.touk.nussknacker.engine.canonicalgraph.{CanonicalProcess, canonicalnode}
import pl.touk.nussknacker.engine.canonize.ProcessCanonizer
import pl.touk.nussknacker.engine.compile.SubprocessResolver
import pl.touk.nussknacker.engine.definition.DefinitionExtractor
import pl.touk.nussknacker.engine.graph.EspProcess
import pl.touk.nussknacker.engine.graph.node.SubprocessInputDefinition.{SubprocessClazzRef, SubprocessParameter}
import pl.touk.nussknacker.engine.graph.node._
import pl.touk.nussknacker.engine.graph.sink.SinkRef
import pl.touk.nussknacker.engine.process.ProcessTestHelpers.{MockService, SimpleRecord, processInvoker}

class SubprocessSpec extends FlatSpec with Matchers {

  import pl.touk.nussknacker.engine.spel.Implicits._

  it should "should accept same id in subprocess and main process " in {

    val process = resolve(EspProcessBuilder.id("proc1")
      .exceptionHandler()
      .source("id", "input")
      .subprocessOneOut("sub", "subProcess1", "output", "param" -> "#input.value2")
      .processorEnd("end1", "logService", "all" -> "#input.value2"))

    val data = List(
      SimpleRecord("1", 12, "a", new Date(0))
    )

    processInvoker.invoke(process, data)

    MockService.data shouldNot be('empty)
    MockService.data.head shouldBe "a"
  }

  it should "should handle split in subprocess" in {

    val process = resolve(EspProcessBuilder.id("proc1")
      .exceptionHandler()
      .source("id", "input")
      .subprocessOneOut("sub", "splitSubprocess", "output", "param" -> "#input.value2")
      .processorEnd("end1", "logService", "all" -> "#input.value2"))

    val data = List(
      SimpleRecord("1", 12, "a", new Date(0))
    )

    processInvoker.invoke(process, data)

    MockService.data shouldNot be('empty)
    MockService.data.head shouldBe "a"
  }

  private def resolve(espProcess: EspProcess) = {
    val subprocess = CanonicalProcess(MetaData("subProcess1", StreamMetaData()), null,
      List(
        canonicalnode.FlatNode(SubprocessInputDefinition("start", List(SubprocessParameter("param", SubprocessClazzRef[String])))),
        canonicalnode.FilterNode(Filter("f1", "#param == 'a'"),
        List(canonicalnode.FlatNode(Sink("end1", SinkRef("monitor", List()), Some("'deadEnd'"))))
      ), canonicalnode.FlatNode(SubprocessOutputDefinition("out1", "output"))))

    val subprocessWithSplit = CanonicalProcess(MetaData("splitSubprocess", StreamMetaData()), null,
      List(
        canonicalnode.FlatNode(SubprocessInputDefinition("start", List(SubprocessParameter("param", SubprocessClazzRef[String])))),
        canonicalnode.SplitNode(Split("split"), List(
          List(canonicalnode.FlatNode(Sink("end1", SinkRef("monitor", List())))),
          List(canonicalnode.FlatNode(SubprocessOutputDefinition("out1", "output")))
        ))
      ))

    val resolved = SubprocessResolver(Set(subprocessWithSplit, subprocess)).resolve(ProcessCanonizer.canonize(espProcess))
      .andThen(ProcessCanonizer.uncanonize)

    resolved shouldBe 'valid

    resolved.toOption.get
  }


}
