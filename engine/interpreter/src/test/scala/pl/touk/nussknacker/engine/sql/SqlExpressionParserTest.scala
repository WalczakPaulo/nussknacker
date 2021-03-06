package pl.touk.nussknacker.engine.sql

import cats.data.Validated.{Invalid, Valid}
import org.scalatest.{FunSuite, Matchers}
import pl.touk.nussknacker.engine.api.typed.{ClazzRef, typing}
import pl.touk.nussknacker.engine.api.typed.typing.{Typed, Unknown}
import pl.touk.nussknacker.engine.compile.ValidationContext

class SqlExpressionParserTest extends FunSuite with Matchers {


  val validationCtx = ValidationContext(Map(
    "table1" -> TypedList(Map("col1" -> Typed[String])),
    "table1a" -> TypedList(Map("col1" -> Typed[Long])),
    "table2" -> TypedList(Map("stringField" -> Typed[String], "longField" -> Typed[Long]))
  ))

  test("valid query") {
    SqlExpressionParser.parse("select * from table1", validationCtx, Unknown) shouldBe a[Valid[_]]
  }
  test("query with unexisting table variable should invalidates") {
    SqlExpressionParser.parse("select * from unicorn", validationCtx, Unknown) shouldBe a[Invalid[_]]
  }
  test("query with unexisting select column should invalidates") {
    SqlExpressionParser.parse("select unicorn from table1", validationCtx, Unknown) shouldBe a[Invalid[_]]
  }


  test("find smallest tables set") {
    parseOrFail("select stringField from table2")._2.columnModels.keySet shouldEqual Set("table2")
    parseOrFail("select col1, stringField from table1 t1, table2 t2")._2.columnModels.keySet shouldEqual Set("table1", "table2")
  }

  private def parseOrFail(expression: String, ctx: ValidationContext = validationCtx): (typing.TypingResult, SqlExpression)
    = SqlExpressionParser.parse(expression, ctx, Unknown).leftMap(err => fail(s"Failed to parse: $err")).merge

}
