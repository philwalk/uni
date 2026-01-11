package uni.util

import scala.util.DynamicVariable

// public API
export ArgCtx.{thisArg, consumeNext, peekNext, nextInt, nextLong, nextDouble}
def eachArg(args: Seq[String], usage: String => Nothing)
           (pf: PartialFunction[String, Unit]): Unit = {
  ArgCtx.withArgs(args, usage)(pf)
}

private object ArgCtx {

  private final class Ctx(args: Seq[String], usage: String => Nothing) {
    private var i: Int = 0

    def thisArg: String = args(i)

    private inline def withNext[A](onOk: => A): A = {
      if i + 1 < args.length then {
        onOk
      } else {
        usage(s"missing argument after [$thisArg]")
      }
    }

    def consumeNext: String = {
      withNext {
        i += 1
        args(i)
      }
    }

    def peekNext: String = {
      withNext {
        args(i + 1)
      }
    }

    def nextInt: Int = {
      consumeNext.toIntOption.getOrElse {
        usage(s"expected Int after [$thisArg]")
      }
    }

    def nextLong: Long = {
      consumeNext.toLongOption.getOrElse {
        usage(s"expected Long after [$thisArg]")
      }
    }

    def nextDouble: Double = {
      consumeNext.toDoubleOption.getOrElse {
        usage(s"expected Double after [$thisArg]")
      }
    }

    def run(pf: PartialFunction[String, Unit]): Unit = {
      while i < args.length do {
        val arg = thisArg
        if pf.isDefinedAt(arg) then
          pf(arg)
        else
          usage(s"unknown argument [$arg]")
        i += 1
      }
    }
  }

  private val current = new DynamicVariable[Ctx | Null](null)

  def withArgs[A](args: Seq[String], usage: String => Nothing)
                 (pf: PartialFunction[String, Unit]): A = {
    val ctx = new Ctx(args, usage)
    current.withValue(ctx) {
      ctx.run(pf)
    }.asInstanceOf[A]
  }

  private def ctx: Ctx = {
    current.value match {
      case c: Ctx => c
      case null =>
        throw new IllegalStateException("argument helpers used outside eachArg")
    }
  }

  def thisArg: String = ctx.thisArg

  def consumeNext: String = ctx.consumeNext

  def peekNext: String = ctx.peekNext

  def nextInt: Int = ctx.nextInt

  def nextLong: Long = ctx.nextLong

  def nextDouble: Double = ctx.nextDouble

}
