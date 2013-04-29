package org.jetbrains.plugins.scala
package codeInspection.booleans

import org.jetbrains.plugins.scala.codeInspection.{AbstractFix, AbstractInspection}
import com.intellij.codeInspection.{ProblemHighlightType, ProblemDescriptor, ProblemsHolder}
import com.intellij.psi.PsiElement
import org.jetbrains.plugins.scala.lang.psi.api.expr._
import org.jetbrains.plugins.scala.lang.psi.api.base.ScLiteral
import org.jetbrains.plugins.scala.lang.psi.impl.ScalaPsiElementFactory
import scala.Predef._
import com.intellij.openapi.project.Project
import org.jetbrains.plugins.scala.lang.completion.ScalaKeyword
import org.jetbrains.plugins.scala.lang.psi.types.result.TypingContext
import scala.Some

/**
 * Nikolay.Tropin
 * 4/23/13
 *
 */
class SimplifyBooleanInspection extends AbstractInspection("SimplifyBoolean", "Simplify boolean expression"){

  def actionFor(holder: ProblemsHolder): PartialFunction[PsiElement, Any] = {
    case parenthesized: ScParenthesisedExpr => //do nothing to avoid many similar expressions
    case expr: ScExpression if (SimplifyBooleanUtil.canBeSimplified(expr)) =>
        holder.registerProblem(expr, "Simplify boolean expression", ProblemHighlightType.GENERIC_ERROR_OR_WARNING, new SimplifyBooleanQuickFix(expr))
  }

}

class SimplifyBooleanQuickFix(expr: ScExpression) extends AbstractFix("Simplify " + expr.getText, expr) {

  def doApplyFix(project: Project, descriptor: ProblemDescriptor) {
    if (!expr.isValid || !SimplifyBooleanUtil.canBeSimplified(expr)) return
    else {
      val simplified = SimplifyBooleanUtil.simplify(expr)
      expr.replaceExpression(simplified, removeParenthesis = true)
    }
  }
}

object SimplifyBooleanUtil {
  val boolInfixOperations = List("==", "!=", "&&", "&", "||", "|", "^")

  def canBeSimplified(expr: ScExpression, isTopLevel: Boolean = true): Boolean = {
    expr match {
      case _: ScLiteral if !isTopLevel => booleanConst(expr).isDefined
      case expression: ScExpression =>
        val children = getScExprChildren(expr)
        val isBooleanOperation = expression match {
          case prExpr: ScPrefixExpr => prExpr.operation.refName == "!"
          case infExpr: ScInfixExpr => boolInfixOperations contains infExpr.operation.refName
          case _ => false
        }
        isOfBooleanType(expr) && isBooleanOperation && children.exists(canBeSimplified(_, isTopLevel = false))
    }
  }

  def simplify(expr: ScExpression): ScExpression = {
    val exprCopy = expr.copy.asInstanceOf[ScExpression]
    val children = getScExprChildren(exprCopy)
    children.map(child => exprCopy.getNode.replaceChild(child.getNode, simplify(child).getNode))
    simplifyTrivially(exprCopy)
  }

  private def isOfBooleanType(expr: ScExpression): Boolean = expr.getType(TypingContext.empty).getOrAny.equiv(lang.psi.types.Boolean)

  private def getScExprChildren(expr: ScExpression) = expr.children.filter(_.isInstanceOf[ScExpression]).map(_.asInstanceOf[ScExpression]).toList

  private def booleanConst(expr: ScExpression): Option[Boolean] = expr match {
    case literal: ScLiteral =>
      literal.getText match {
        case "true" => Some(true)
        case "false" => Some(false)
        case _ => None
      }
    case _ => None
  }

  private def simplifyTrivially(expr: ScExpression): ScExpression = expr match {
    case parenthesized: ScParenthesisedExpr =>
      val copy = parenthesized.copy.asInstanceOf[ScParenthesisedExpr]
      copy.replaceExpression(copy.expr.getOrElse(copy), removeParenthesis = true)
    case prefixExpr: ScPrefixExpr =>
      if (prefixExpr.operation.refName != "!") prefixExpr
      else {
        booleanConst(prefixExpr.operand) match {
          case Some(bool: Boolean) =>
            ScalaPsiElementFactory.createExpressionFromText((!bool).toString, expr.getManager)
          case None => prefixExpr
        }
      }
    case infixExpr: ScInfixExpr =>
      val oper = infixExpr.operation.refName
      if (!boolInfixOperations.contains(oper)) infixExpr
      else {
        val leftExpr: ScExpression = infixExpr.lOp
        val rightExpr: ScExpression = infixExpr.rOp
        booleanConst(leftExpr) match {
          case Some(bool: Boolean) => simplifyInfixWithLiteral(bool, oper, rightExpr)
          case None => booleanConst(rightExpr) match {
            case Some(bool: Boolean) => simplifyInfixWithLiteral(bool, oper, leftExpr)
            case None => infixExpr
          }
        }
      }
    case _ => expr
  }

  private def simplifyInfixWithLiteral(value: Boolean, operation: String, expr: ScExpression): ScExpression = {
    val manager = expr.getManager
    val text: String = booleanConst(expr) match {
      case Some(bool: Boolean) =>
        val result: Boolean = operation match {
          case "==" => bool == value
          case "!=" | "^" => bool != value
          case "&&" | "&" => bool && value
          case "||" | "|" => bool || value
        }
        result.toString
      case _ => (value, operation) match {
        case (true, "==") | (false, "!=") | (false, "^") | (true, "&&") | (true, "&") | (false, "||") | (false, "|")  => expr.getText
        case (false, "==") | (true, "!=") | (true, "^") =>
          val negated: ScPrefixExpr = ScalaPsiElementFactory.createExpressionFromText("!a", manager).asInstanceOf[ScPrefixExpr]
          val copyExpr = expr.copy.asInstanceOf[ScExpression]
          negated.operand.replaceExpression(copyExpr, removeParenthesis = true)
          negated.getText
        case (true, "||") | (true, "|") =>
          ScalaKeyword.TRUE
        case (false, "&&") | (false, "&") =>
          ScalaKeyword.FALSE
        case _ => throw new IllegalArgumentException("Wrong operation")
      }
    }
    ScalaPsiElementFactory.createExpressionFromText(text, manager)

  }
}