package uni.cli

import scala.quoted.*

final case class CallerInfo(
  path: String,
  line: Int,
  column: Int,
  owner: String
)

/*
 * If referenced by an inline method, the source path of the method caller.
 * Additional available fields:
 *   pos.startLine      
 *   pos.startColumn
 *   Symbol.spliceOwner.owner.owner.fullName
 */
inline def currentCaller: String = ${ currentCallerImpl }

def currentCallerImpl(using Quotes): Expr[String] =
  import quotes.reflect.*
  val pos = Position.ofMacroExpansion
  Expr(pos.sourceFile.path)

inline def currentCallerInfo: CallerInfo = ${ currentCallerInfoImpl }

def currentCallerInfoImpl(using Quotes): Expr[CallerInfo] =
  import quotes.reflect.*

  val pos = Position.ofMacroExpansion

  // The real caller is usually two owners up:
  // spliceOwner = synthetic lambda
  // owner = enclosing method
  // owner.owner = enclosing class/object
  val ownerSym = Symbol.spliceOwner.owner.owner
  val ownerName = ownerSym.fullName

  val path: String = pos.sourceFile.path
  val line: Int    = pos.startLine
  val column: Int  = pos.startColumn

  // Construct the case class expression
  '{
    CallerInfo(
      path   = ${Expr(path)},
      line   = ${Expr(line)},
      column = ${Expr(column)},
      owner  = ${Expr(ownerName)}
    )
  }
