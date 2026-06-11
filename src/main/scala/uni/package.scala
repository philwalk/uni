package uni

export cli.ArgsParser.*
export pathExts.*
export stringExts.*
export helpers.*
export uni.data.{MatD, MatB, MatF, CVecD, RVecD, CVecF, RVecF, CVecB, RVecB, CVec, RVec, MatElem}
export uni.io.{AggOp, JoinType}
export io.matResultOps.*
def cksum(bytes: Array[Byte]): (Long, Long)    = io.Cksum.cksum(bytes)
def cksum(bytes: Iterator[Byte]): (Long, Long) = io.Cksum.cksum(bytes)

