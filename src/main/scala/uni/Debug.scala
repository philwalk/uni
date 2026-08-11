package uni

/** Conditional-breakpoint anchor: client code writes `if suspicious then hook += 1`
 *  to have a statement a debugger can stop on exactly when a condition of interest
 *  holds. It carries no semantics and nothing should ever read it — a generic
 *  debugging device, deliberately part of the public surface (hundreds of scripts
 *  in the working corpus use it), and deliberately NOT housed in any functional
 *  module: it is not related to what any of them do.
 */
var hook: Int = 0
