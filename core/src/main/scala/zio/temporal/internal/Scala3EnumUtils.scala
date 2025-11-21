package zio.temporal.internal

import zio.temporal.internal.MacroUtils
import zio.temporal.internalApi

import scala.quoted.*
import scala.reflect.Enum

@internalApi
object Scala3EnumUtils {

  @internalApi
  final case class Scala3EnumMeta[E <: Enum](name: String, valueOf: String => E)

  @internalApi
  inline def getEnumMeta[E <: Enum]: Scala3EnumMeta[E] =
    ${ getEnumMetaImpl[E] }

  private def getEnumMetaImpl[E <: Enum: Type](using q: Quotes): Expr[Scala3EnumMeta[E]] = {
    import q.reflect.*
    val macroUtils = new MacroUtils[q.type]
    import macroUtils.*
    val tpe          = TypeRepr.of[E]
    val enumName     = tpe.show
    val enumClassSym = tpe.classSymbol.getOrElse(error(s"$enumName is not a enum!"))

    val valueOfSym = enumClassSym.companionClass
      .methodMember("valueOf")
      .headOption
      .getOrElse(error(s"$enumName companion object doesn't have valueOf method"))

    val parse = Lambda(
      Symbol.spliceOwner,
      MethodType(
        List("v")
      )(
        _ => List(TypeRepr.of[String]),
        _ => tpe
      ),
      (_, params) => Apply(companionObjectOf(tpe).select(valueOfSym), params.map(_.asExpr.asTerm))
    ).asExprOf[String => E]

    '{
      Scala3EnumMeta[E](${ Expr(enumName) }, ${ parse })
    }.debugged("Generated Scala 3 Enum Meta")
  }
}
