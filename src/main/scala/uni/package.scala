package uni

export cli.ArgsParser.*
export pathExts.*
export stringExts.*
export helpers.*
export uni.data.{MatD, MatB, MatF, CVecD, RVecD, CVecF, RVecF, CVecB, RVecB, CVec, RVec, MatElem}
// CVec/RVec extension methods: the companions are package-level objects (not
// companions of the opaque types, which live inside object Mat), so these are
// NOT in implicit scope — `import uni.*` clients need this explicit forward.
export uni.data.VecOps.*
export uni.io.{AggOp, JoinType}
export io.matResultOps.*
def cksum(bytes: Array[Byte]): (Long, Long)    = io.Cksum.cksum(bytes)
def cksum(bytes: Iterator[Byte]): (Long, Long) = io.Cksum.cksum(bytes)

