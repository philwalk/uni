package uni.data

export FloatingPointExts.*

/** Non-boxing `isNaN` / `isInfinite` for `Double` and `Float`.
 *
 *  Without these, `x.isNaN` resolves to `Predef.double2Double(x).isNaN()`
 *  (a `java.lang.Double` box), because `Predef`'s boxing conversion outranks
 *  `RichDouble`. An extension in lexical scope is tried before either.
 */
object FloatingPointExts:
  extension (d: Double)
    inline def isNaN: Boolean = java.lang.Double.isNaN(d)
    inline def isInfinite: Boolean = java.lang.Double.isInfinite(d)

  extension (f: Float)
    inline def isNaN: Boolean = java.lang.Float.isNaN(f)
    inline def isInfinite: Boolean = java.lang.Float.isInfinite(f)
