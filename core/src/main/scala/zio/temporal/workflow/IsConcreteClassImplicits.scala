package zio.temporal.workflow

import zio.temporal.internal.MacroUtils

import scala.quoted.*
trait IsConcreteClassImplicits {
  inline given materialize[A]: IsConcreteClass[A] =
    ${ IsConcreteClassImplicits.impl[A] }
}

object IsConcreteClassImplicits {
  def impl[A: Type](using q: Quotes): Expr[IsConcreteClass[A]] = {
    import q.reflect.*
    val macroUtils = new MacroUtils[q.type]
    macroUtils.assertConcreteClass(TypeRepr.of[A])
    '{ IsConcreteClass.__zio_temporal_IsConcreteClassInstance.asInstanceOf[IsConcreteClass[A]] }
  }
}
