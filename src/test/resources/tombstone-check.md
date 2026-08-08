# Verifying the `M(rows, cols)` tombstone

`MatFacade.apply(rows: Int, cols: Int)` is a `@compileTimeOnly` tombstone
(`src/main/scala/uni/data/MatFacades.scala`). Calling it must fail to compile
with a message naming the replacements.

**This cannot be covered by a unit test.** munit's `compileErrors` is built on
`scala.compiletime.testing.typeCheckErrors`, which runs the **typer** only, while
`@compileTimeOnly` is enforced in a later phase (PostTyper). The call type-checks
cleanly, so `compileErrors` returns `""` and a test asserting on it silently
passes no matter what. Verify with a real compile instead.

## Reproduction

```bash
sbt --client compile

cat > /tmp/tomb.scala <<'EOF'
import uni.data.*
object Tomb:
  def main(a: Array[String]): Unit =
    val m = MatD(3, 4)
    println(m.rows)
EOF

scala-cli compile --classpath target/scala-3.7.0/classes /tmp/tomb.scala
```

Expected — compilation fails with:

```
M(rows, cols) is ambiguous and was removed in v0.15.0: two Int arguments used to
mean dimensions, while Ints at any other arity — and Doubles at any arity — mean
values. Use `.zeros(rows, cols)` for a zero matrix, `.empty` for 0x0, or pass
Doubles (e.g. MatD(3.0, 4.0)) for a 2x1 column of values.
```

The same applies to `MatF(3, 4)` and `MatB(3, 4)` — the tombstone is on the
shared `MatFacade` trait.

## Why a tombstone rather than a deletion

Deleting the overload would leave `MatD(3, 4)` **compiling**, resolving instead
to `apply(first: Double, rest: Double*)` and silently producing a 2×1 column of
`3.0, 4.0` rather than a 3×4 zero matrix. The tombstone converts that silent
change of shape into a loud error. Remove the method only once downstream code
has had time to migrate.
