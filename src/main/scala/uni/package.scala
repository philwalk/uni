package uni

export cli.ArgsParser.*
export pathExts.*
export stringExts.*
export helpers.*
export uni.data.{MatD, MatB, MatF, CVecD, RVecD, CVecF, RVecF, CVecB, RVecB, CVec, RVec, MatElem}
// Do NOT re-export uni.data.VecOps.* here: uni.data already exports it at
// package level (VecExts.scala), and a second forwarder makes every VecOps
// name ambiguous (E049) for clients that import both uni.* and uni.data.*.
// `import uni.*`-only clients still reach Mat methods on CVec/RVec values via
// object Mat's companion implicit scope (CVec[T] <: Mat[T]); vector-typed
// dispatch (.T: RVec, etc.) requires `import uni.data.*`.
export uni.io.{AggOp, JoinType}
export io.matResultOps.*
def cksum(bytes: Array[Byte]): (Long, Long)    = io.Cksum.cksum(bytes)
def cksum(bytes: Iterator[Byte]): (Long, Long) = io.Cksum.cksum(bytes)

