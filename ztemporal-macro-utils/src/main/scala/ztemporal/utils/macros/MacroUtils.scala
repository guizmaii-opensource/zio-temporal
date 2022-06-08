package ztemporal.utils.macros

import scala.reflect.macros.TypecheckException
import scala.reflect.macros.blackbox

abstract class MacroUtils(val c: blackbox.Context) {
  import c.universe._

  def extractSelectorField(t: Tree): Option[TermName] =
    t match {
      case q"(${vd: ValDef}) => ${idt: Ident}.${fieldName: TermName}" if vd.name == idt.name =>
        Some(fieldName)
      case _ =>
        None
    }

  def extractMethodSelector0(t: Tree): Option[TermName] =
    t match {
      case q"(${vd: ValDef}) =>  { (${idt: Ident}: ${tpe} ).${method} }" if vd.name == idt.name =>
        Some(method)

      case _ =>
        None
    }

  def extractMethodSelector1(t: Tree): Option[TermName] =
    t match {
      case q"(${vd: ValDef}) =>  { (${p0: ValDef}) => (${idt: Ident}: ${tpe} ).${method}(${arg0: Ident}) }"
          if vd.name == idt.name && p0.name == arg0.name =>
        Some(method)

      case _ =>
        None
    }

  def extractMethodSelector2(t: Tree): Option[TermName] =
    t match {
      case q"(${vd: ValDef}) =>  { (${p0: ValDef}, ${p1: ValDef}) => (${idt: Ident}: ${tpe} ).${method}(${arg0: Ident}, ${arg1: Ident}) }"
          if vd.name == idt.name && p0.name == arg0.name && p1.name == arg1.name =>
        Some(method)

      case _ =>
        None
    }

  def extractMethod0(t: Tree): Option[(Type, TermName)] =
    t match {
      case q" ${idt: Ident}.${method} " =>
        Some(idt.tpe -> method)

      case q" ${idt: Ident}.${method}()" =>
        Some(idt.tpe -> method)

      case _ =>
        None
    }

  def extractMethod1(t: Tree): Option[(Type, TermName)] =
    t match {
      case q"{ (${p0: ValDef}) => ${idt: Ident}.${method}(${arg0: Ident}) } " if p0.name == arg0.name =>
        Some(idt.tpe -> method)

      case _ =>
        println(t.getClass)
        None
    }

  def extractMethod2(t: Tree): Option[(Type, TermName)] =
    t match {
      case q" (${p0: ValDef}, ${p1: ValDef}) => ${idt: Ident}.${method}(${arg0: Ident}, ${arg1: Ident}) "
          if p0.name == arg0.name && p1.name == arg1.name =>
        Some(idt.tpe -> method)

      case _ =>
        None
    }

  def findAnnotation(sym: Symbol, annotationType: Type): Option[Tree] =
    sym.annotations
      .collectFirst {
        case annotation if annotation.tree.tpe =:= annotationType =>
          annotation.tree
      }

  def getAnnotation(sym: Symbol, annotationType: Type): Tree =
    findAnnotation(sym, annotationType)
      .getOrElse(error(s"$sym has no $annotationType annotation"))

  def getMethodAnnotation(tpe: Type, methodName: TermName, annotationType: Type): Tree = {
    val methodDecl = tpe.decls
      .find(_.name == methodName)
      .getOrElse(error(s"method $methodName not found in $tpe"))

    if (!methodDecl.isMethod) error(s"$methodName of $tpe is not a method")
    else getAnnotation(methodDecl, annotationType)
  }

  def findImplicit(tpe: Type, errorMessage: => String): Tree =
    try c.inferImplicitValue(tpe, silent = false)
    catch {
      case _: TypecheckException =>
        error(errorMessage)
    }

  def freshTermName(name: String): TermName = c.freshName(TermName(name))

  def error(message: String): Nothing = c.abort(c.enclosingPosition, message)

  private def debugEnabled: Boolean =
    sys.props
      .get("ztemporal.debug.macro")
      .flatMap(str => scala.util.Try(str.toBoolean).toOption)
      .getOrElse(false)

  implicit class Debugged[A](self: A) {

    def debugged(msg: String): A = {
      if (debugEnabled)
        c.info(c.enclosingPosition, s"$msg tree=$self", force = true)
      self
    }
  }
}
