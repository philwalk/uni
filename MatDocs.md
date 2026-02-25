# Mat API Reference

*Auto-generated from test suite*


## Boolean

- `num2string formats normally`

## Indexing

- `Big.apply parses valid numeric strings`
- `Big.apply returns BadNum for invalid strings`

## Shape

- `numStrPct formats percentages`

## Linear Algebra

- `num2string formats normally`

## Random

- `num2string formats normally`

## Element-wise Math

- `setScale should round to specified decimal places`
- `setScale with HALF_DOWN rounding mode`
- `setScale with HALF_UP rounding mode`

## NumPy Compatibility

Tests demonstrating NumPy compatibility:


## Test Coverage

Total tests: 22
- Boolean: 1 tests
- Indexing: 2 tests
- Shape: 1 tests
- Linear Algebra: 1 tests
- Random: 1 tests
- Element-wise Math: 3 tests
# Mat API Reference

*Auto-generated from test suite*


## Arithmetic

- `apply generic scalar creates 1x1`
- `matrix factory taking a single scalar value of type Big`
- `matrix factory taking a single scalar value of type Double`

## Comparison

- `arange(start, stop) default Double`
- `arange(start, stop, step) default Double`
- `arange(stop) default Double`
- `col(Int*) default Double`
- `eye default Double`
- `full default Double`
- `ones default Double`
- `row(Int*) default Double`

## Indexing

- `apply from flat array with dimensions`
- `apply generic scalar creates 1x1`
- `apply rejects unsupported type in tuple`
- `apply with BigDecimal literals`
- `apply with Float literals`
- `apply(Double) creates 1x1 Mat[Double]`
- `apply(unit) with Float type`

## Shape

- `apply from flat array with dimensions`
- `apply with Float literals`
- `apply(unit) with Float type`
- `arange(start, stop) default Double`
- `arange(start, stop, step) default Double`
- `arange(stop) default Double`
- `col(Int*) default Double`
- `eye default Double`
- `full default Double`
- `ones default Double`
- `row(Int*) default Double`

## Creation

- `arange(start, stop) default Double`
- `arange(start, stop, step) default Double`
- `arange(stop) default Double`
- `empty[Float] creates 0x0 matrix`
- `eye default Double`
- `fromSeq with non-empty sequence`
- `full default Double`
- `linspace negative range`
- `matrix factory taking a single scalar value of type Big`
- `matrix factory taking a single scalar value of type Double`
- `of creates row vector`
- `ones default Double`
- `single creates 1x1 Float`
- `tabulate with Double`

## Element-wise Math

- `matrix factory taking a single scalar value of type Big`
- `matrix factory taking a single scalar value of type Double`
- `single creates 1x1 Float`

## NumPy Compatibility

Tests demonstrating NumPy compatibility:


## Test Coverage

Total tests: 25
- Arithmetic: 3 tests
- Comparison: 8 tests
- Indexing: 7 tests
- Shape: 11 tests
- Creation: 14 tests
- Element-wise Math: 3 tests
# Mat API Reference

*Auto-generated from test suite*


## Arithmetic

- `dot is alias for matrix multiplication`
- `element-wise multiplication`
- `matrix multiplication`
- `matrix multiplication rejects invalid dimensions`
- `matrix multiplication with non-square`

## Comparison

- `Mat[Double] ~@ Mat[Double] matrix multiply promotes to Mat[Double]`
- `dot is alias for matrix multiplication`
- `element-wise multiplication`
- `matrix multiplication`
- `matrix multiplication rejects invalid dimensions`
- `matrix multiplication with non-square`

## Shape

- `dot is alias for matrix multiplication`

## Linear Algebra

- `Mat[Double] ~@ Double promotes to Mat[Double]`
- `Mat[Double] ~@ Mat[Double] matrix multiply promotes to Mat[Double]`
- `dot is alias for matrix multiplication`
- `matrix multiplication`
- `matrix multiplication rejects invalid dimensions`
- `matrix multiplication with non-square`

## NumPy Compatibility

Tests demonstrating NumPy compatibility:


## Test Coverage

Total tests: 8
- Arithmetic: 5 tests
- Comparison: 6 tests
- Shape: 1 tests
- Linear Algebra: 6 tests
# Mat API Reference

*Auto-generated from test suite*


## Comparison

- `setPrintOptions default values restore original behavior`
- `setPrintOptions multiple parameters at once`

## Display

- `formatMatrix does not truncate small matrices`
- `formatMatrix empty matrix shows header only`
- `formatMatrix handles various numeric types with truncation`
- `formatMatrix preserves alignment with ellipsis`
- `formatMatrix row ellipsis appears between edge rows`
- `formatMatrix threshold exactly at boundary`
- `formatMatrix truncates both rows and columns`
- `formatMatrix truncates large column count with ellipsis`
- `formatMatrix truncates large row count with ellipsis`
- `formatMatrix with explicit format and truncation`
- `setPrintOptions can disable truncation entirely`
- `setPrintOptions changes edge items displayed`
- `setPrintOptions changes max cols threshold`
- `setPrintOptions changes max rows threshold`
- `setPrintOptions default values restore original behavior`
- `setPrintOptions multiple parameters at once`
- `setPrintOptions precision affects decimal places`
- `setPrintOptions thread safety - options are global`
- `setPrintOptions threshold controls total element cutoff`
- `show formats Big matrix nicely`
- `show formats Float matrix nicely`
- `show formats matrix nicely with wider range`
- `show handles empty matrix of type Big`
- `show handles empty matrix of type Float`
- `show precision reflects spread`
- `show respects transposition`
- `show uses integer format for whole numbers`
- `show uses scientific notation for large values`
- `show uses scientific notation for small values`
- `show: 0x0 Big (auto)`
- `show: 0x0 Big (fmt)`
- `show: 0x0 Double (auto)`
- `show: 0x0 Double (fmt)`
- `show: 0x0 Float (auto)`
- `show: 0x0 Float (fmt)`
- `show: 2x2 Big (auto)`
- `show: 2x2 Big (fmt)`
- `show: 2x2 Double (auto)`
- `show: 2x2 Double (fmt)`
- `show: 2x2 Float (auto)`
- `show: 2x2 Float (fmt)`
- `show: mixed magnitudes (auto)`
- `show: mixed magnitudes (fmt)`
- `show: tall Double (auto)`
- `show: tall Double (fmt)`
- `show: transposed (auto)`
- `show: transposed (fmt)`
- `show: wide Double (auto)`
- `show: wide Double (fmt)`

## Boolean

- `formatMatrix does not truncate small matrices`
- `show uses scientific notation for small values`
- `show: tall Double (auto)`
- `show: tall Double (fmt)`

## Shape

- `formatMatrix does not truncate small matrices`
- `formatMatrix preserves alignment with ellipsis`
- `formatMatrix threshold exactly at boundary`
- `formatMatrix truncates large column count with ellipsis`
- `formatMatrix truncates large row count with ellipsis`
- `formatMatrix with explicit format and truncation`
- `setPrintOptions default values restore original behavior`
- `setPrintOptions multiple parameters at once`
- `setPrintOptions threshold controls total element cutoff`
- `show formats Float matrix nicely`
- `show uses integer format for whole numbers`
- `show: 0x0 Float (auto)`
- `show: 0x0 Float (fmt)`
- `show: 2x2 Float (auto)`
- `show: 2x2 Float (fmt)`
- `show: transposed (auto)`
- `show: transposed (fmt)`

## Creation

- `formatMatrix empty matrix shows header only`
- `setPrintOptions threshold controls total element cutoff`
- `show handles empty matrix of type Big`
- `show handles empty matrix of type Float`

## Statistics

- `setPrintOptions changes max cols threshold`
- `setPrintOptions changes max rows threshold`

## Element-wise Math

- `formatMatrix does not truncate small matrices`
- `formatMatrix handles various numeric types with truncation`
- `formatMatrix truncates both rows and columns`
- `formatMatrix truncates large column count with ellipsis`
- `formatMatrix truncates large row count with ellipsis`
- `formatMatrix with explicit format and truncation`
- `setPrintOptions can disable truncation entirely`

## NumPy Compatibility

Tests demonstrating NumPy compatibility:


## Test Coverage

Total tests: 49
- Comparison: 2 tests
- Display: 49 tests
- Boolean: 4 tests
- Shape: 17 tests
- Creation: 4 tests
- Statistics: 2 tests
- Element-wise Math: 7 tests
# Mat API Reference

*Auto-generated from test suite*


## Arithmetic

- `*= Int scalar`
- `*= scalar`
- `+= Int scalar`
- `+= Mat element-wise`
- `+= scalar`
- `-= Int scalar`
- `-= Mat element-wise`
- `-= scalar`
- `/= Int scalar`
- `/= scalar`
- `Broadcasting Addition: 3x3 + 3x1`
- `Double ~^ operator precedence with addition`
- `Double ~^ operator precedence with multiplication`
- `Double ~^ precedence with addition`
- `Mat(scalar) automatically creates 1x1 matrix`
- `Mat(scalar) with Double`
- `Mat(scalars...) creates column vector`
- `Mat[Big] scalar operations`
- `Mat[Double] ~^ operator element-wise`
- `Mat[Double] ~^ operator with scalar precedence`
- `addition of matrices`
- `addition rejects mismatched shapes`
- `all rows range cols scalar assignment`
- `broadcasting: division by scalar (1x1 matrix)`
- `cos computes element-wise cosine`
- `dot is alias for matrix multiplication`
- `element-wise multiplication`
- `element-wise multiply rejects mismatched shapes`
- `hadamard correct values`
- `hadamard equals *:*`
- `kron with scalar matrix scales`
- `matrix multiplication`
- `matrix multiplication rejects invalid dimensions`
- `matrix multiplication with non-square`
- `maximum element-wise between two matrices`
- `maximum with scalar - ReLU pattern`
- `minimum element-wise between two matrices`
- `minimum with scalar - clipping`
- `range rows all cols scalar assignment`
- `range rows range cols scalar assignment`
- `scalar addition`
- `scalar division`
- `scalar multiplication`
- `scalar subtraction`
- `sin computes element-wise sine`
- `subtraction of matrices`
- `subtraction rejects mismatched shapes`
- `tan computes element-wise tangent`
- `tanh computes element-wise hyperbolic tangent`
- `unary negation`
- `where with Int scalars`
- `where with scalar arguments`

## Comparison

- `:!= returns correct boolean mask`
- `:== returns correct boolean mask`
- `:== with Int argument works`
- `BLAS multiply matches pure JVM for transposed matrices`
- `BLAS multiply matches pure JVM multiply`
- `Double ~^ operator precedence with multiplication`
- `allclose custom tolerance`
- `allclose identical matrices`
- `allclose outside tolerance`
- `allclose shape mismatch returns false`
- `allclose within tolerance`
- `boolean mask assignment with :== `
- `cholesky result is lower triangular`
- `convolve with identity filter`
- `correlate valid mode default`
- `cross product known result`
- `dot is alias for matrix multiplication`
- `element-wise multiplication`
- `element-wise multiply rejects mismatched shapes`
- `gt returns correct boolean mask`
- `gt with Int argument works`
- `gte includes boundary`
- `histogram with default 10 bins`
- `kron known result`
- `linspace defaults to 50 points`
- `lt returns correct boolean mask`
- `lt with Int argument works`
- `lte includes boundary`
- `matrix multiplication`
- `matrix multiplication rejects invalid dimensions`
- `matrix multiplication with non-square`
- `median of even-length vector interpolates`
- `mulEachCol multiplies each col by col vector`
- `mulEachRow multiplies each row by row vector`
- `nanToNum replaces NaN with 0 by default`
- `polyfit x y length mismatch throws`
- `rand different seeds give different results`
- `scalar multiplication`
- `solve multiple RHS columns`

## Boolean

- `Big ~^ fractional exponent falls back to Double`
- `Mat(scalar) automatically creates 1x1 matrix`
- `all and any work with single element`
- `all returns false when any element is false`
- `all returns true when all elements are true`
- `all rows range cols`
- `all rows range cols Mat assignment`
- `all rows range cols scalar assignment`
- `all(axis=0) checks columns`
- `all(axis=1) checks rows`
- `allclose custom tolerance`
- `allclose identical matrices`
- `allclose outside tolerance`
- `allclose shape mismatch returns false`
- `allclose within tolerance`
- `any returns false when all elements are false`
- `any returns true when any element is true`
- `any(axis=0) checks columns`
- `any(axis=1) checks rows`
- `boolean mask indexing with all matches`
- `corrcoef diagonal is all ones`
- `cross product of parallel vectors is zero`
- `dropout with p=0 keeps all values`
- `eig of identity matrix has all eigenvalues 1`
- `eigenvalues of identity matrix are all 1`
- `eye k beyond bounds is all zeros`
- `isfinite is complement of isnan and isinf`
- `isinf detects infinite values`
- `isnan all false for finite matrix`
- `isnan detects NaN values`
- `logSoftmax is numerically stable`
- `nanToNum then isfinite all true`
- `onesLike has same shape and all ones`
- `power 0 gives all ones`
- `range rows all cols`
- `range rows all cols Mat assignment`
- `range rows all cols scalar assignment`
- `svd of identity matrix has singular values all 1`
- `unique of matrix with all same values`
- `zerosLike has same shape and all zeros`

## Indexing

- `:!= returns correct boolean mask`
- `:== returns correct boolean mask`
- `Layout Guard: transpose of a slice should be auto-normalized`
- `apply creates matrix from tuples`
- `apply from flat array rejects mismatched dimensions`
- `apply from flat array with dimensions`
- `apply gets element at row, col`
- `apply rejects jagged rows`
- `apply with Int creates Double matrix`
- `applyAlongAxis axis=0 applies fn to each column`
- `applyAlongAxis axis=0 consistent with sum(0)`
- `applyAlongAxis axis=0 with max`
- `applyAlongAxis axis=1 applies fn to each row`
- `applyAlongAxis axis=1 consistent with sum(1)`
- `applyAlongAxis axis=1 with mean`
- `applyAlongAxis invalid axis throws`
- `boolean mask assignment sets matching elements`
- `boolean mask assignment with :== `
- `boolean mask indexing returns matching elements as flat vector`
- `boolean mask indexing with all matches`
- `boolean mask indexing with no matches returns empty`
- `boolean mask shape mismatch throws`
- `column slice rejects out of bounds`
- `fancy col indexing out of bounds throws`
- `fancy col indexing selects correct cols`
- `fancy indexing preserves order of indices`
- `fancy row indexing out of bounds throws`
- `fancy row indexing selects correct rows`
- `fancy row+col indexing selects submatrix`
- `gt returns correct boolean mask`
- `lt returns correct boolean mask`
- `mutation: column slice using =`
- `mutation: row slice using =`
- `negative indexing for both`
- `negative indexing for cols`
- `negative indexing for rows`
- `row slice rejects out of bounds`
- `slice assignment shape mismatch throws`
- `slice extracts column`
- `slice extracts last column with negative index`
- `slice extracts last row with negative index`
- `slice extracts row`
- `where preserves shape`
- `where returns indices matching predicate`
- `where shape mismatch throws`
- `where with Int scalars`
- `where with Mat arguments selects elementwise`
- `where with scalar arguments`

## ML Functions

- `dropout in inference mode returns unchanged values`
- `dropout maintains expected value`
- `dropout preserves matrix shape`
- `dropout scales remaining values by 1/(1-p)`
- `dropout with different seeds produces different masks`
- `dropout with p=0 keeps all values`
- `dropout with same seed produces same mask`
- `dropout zeros approximately p fraction of elements`
- `elu is smooth at zero`
- `gelu approximation is reasonable`
- `leakyRelu preserves negative values with scaling`
- `logSoftmax is numerically stable`
- `maximum equals relu for maximum(m, 0)`
- `maximum with scalar - ReLU pattern`
- `relu zeroes negative values`
- `sigmoid handles positive and negative values`
- `softmax handles large values without overflow`
- `softmax sums to 1 along axis`

## Shape

- `*= Int scalar`
- `+= Int scalar`
- `+= Mat does not affect other`
- `+= Mat element-wise`
- `+= Mat shape mismatch throws`
- `-= Int scalar`
- `-= Mat element-wise`
- `-= Mat shape mismatch throws`
- `/= Int scalar`
- `:!= returns correct boolean mask`
- `:== returns correct boolean mask`
- `:== with Int argument works`
- `BLAS multiply matches pure JVM for transposed matrices`
- `Big ~^ fractional exponent falls back to Double`
- `Big ~^ integer exponent uses BigDecimal precision`
- `Float ~^ operator`
- `Int ~^ operator returns Double`
- `Layout Guard: transpose of a slice should be auto-normalized`
- `Mat(()) treats Unit as empty matrix`
- `Mat.normal different parameters produce different distributions`
- `Mat.normal shape is correct`
- `Mat[Big] transpose`
- `Mixed Int literals and Double literals infer Double`
- `NumPy equivalence: transpose`
- `NumPy-like behavior: Int literals, Double operations`
- `T is alias for transpose`
- `Zero-copy slicing should reflect updates to the parent`
- `addToEachCol wrong size throws`
- `addToEachRow wrong size throws`
- `addition rejects mismatched shapes`
- `all returns false when any element is false`
- `all rows range cols Mat assignment`
- `allclose shape mismatch returns false`
- `any returns true when any element is true`
- `apply from flat array rejects mismatched dimensions`
- `apply from flat array with dimensions`
- `apply gets element at row, col`
- `apply with Int creates Double matrix`
- `applyAlongAxis axis=0 consistent with sum(0)`
- `applyAlongAxis axis=1 consistent with sum(1)`
- `arange with start and stop`
- `arctan2 computes 2-argument arctangent`
- `argsort axis=0 returns indices that would sort each column`
- `argsort axis=1 returns indices that would sort each row`
- `argsort no axis returns flat sort indices`
- `boolean mask assignment sets matching elements`
- `boolean mask assignment with :== `
- `boolean mask indexing returns matching elements as flat vector`
- `boolean mask shape mismatch throws`
- `broadcasting preserves shape`
- `cholesky L ~@ L^T = original matrix`
- `cholesky result is lower triangular`
- `clip with equal bounds gives constant matrix`
- `column slice rejects out of bounds`
- `comparison preserves shape`
- `correlate of signal with itself peaks at center`
- `cov requires at least 2 observations`
- `cross product is anti-commutative`
- `cross product known result`
- `cross product non-3D throws`
- `cross product of col vectors`
- `cross product of parallel vectors is zero`
- `cross product of standard basis vectors`
- `cumsum no axis flattens and accumulates`
- `determinant of 1x1 matrix`
- `determinant of 2x2 matrix`
- `determinant of 3x3 matrix`
- `determinant of identity matrix is 1`
- `determinant of singular matrix throws`
- `determinant requires square matrix`
- `diag from non-vector Mat throws`
- `diagonal of transposed matrix`
- `diff axis=0 requires at least 2 rows`
- `diff axis=1 requires at least 2 cols`
- `diff no axis flattens and differences`
- `diff of constant matrix is zeros`
- `dot is alias for matrix multiplication`
- `dropout in inference mode returns unchanged values`
- `dropout maintains expected value`
- `dropout preserves matrix shape`
- `dropout scales remaining values by 1/(1-p)`
- `dropout with different seeds produces different masks`
- `dropout with p=0 keeps all values`
- `dropout with same seed produces same mask`
- `dropout zeros approximately p fraction of elements`
- `element-wise multiply rejects mismatched shapes`
- `elu is smooth at zero`
- `eye k=n-1 has single element in top right`
- `fancy col indexing out of bounds throws`
- `fancy col indexing selects correct cols`
- `fancy row indexing out of bounds throws`
- `fancy row indexing selects correct rows`
- `flatten returns 1D array`
- `full with shape tuple`
- `fullLike has same shape and correct value`
- `gt returns correct boolean mask`
- `gt with Int argument works`
- `hadamard correct values`
- `histogram with default 10 bins`
- `isEmpty detects empty matrix`
- `isfinite is complement of isnan and isinf`
- `kron shape is correct`
- `linspace creates evenly spaced column vector for Float type`
- `lstsq exact solution when square non-singular`
- `lt returns correct boolean mask`
- `lt with Int argument works`
- `maximum/minimum shape mismatch throws`
- `maximum/minimum work with different types`
- `meshgrid produces correct shapes`
- `nanToNum with custom replacement values`
- `ndim always returns 2`
- `norm of Float vector`
- `norm of unit vector`
- `ones with shape tuple`
- `onesLike has same shape and all ones`
- `outer product of row vectors`
- `outer product of two vectors`
- `percentile out of range throws`
- `pinv of singular matrix does not throw`
- `polyfit degree too high throws`
- `polyfit linear fit through exact points`
- `polyfit quadratic fit through exact points`
- `polyfit then polyval reproduces original points`
- `polyfit x y length mismatch throws`
- `polyval constant polynomial`
- `power consistent with sqrt`
- `power negative Int throws`
- `power with Int exponent 2`
- `qr decomposition: Q is orthonormal (Q^T ~@ Q = I)`
- `rand Different seeds produce different sequences`
- `rand Mat RNG matches NumPy seed=0 sequence`
- `rand Mat RNG matches NumPy seed=42 integer sequence`
- `rand Mat.randint generates integers in range`
- `rand Mat.randint matrix form`
- `rand different seeds give different results`
- `rand shape is correct`
- `randn shape is correct`
- `range rows all cols Mat assignment`
- `range rows range cols Mat assignment`
- `ravel returns row vector`
- `repeat axis=0 repeats each row`
- `repeat axis=1 repeats each col`
- `repeat n=1 returns equivalent matrix`
- `repeat no axis repeats each element`
- `reshape changes dimensions`
- `reshape rejects invalid dimensions`
- `row slice rejects out of bounds`
- `shape returns (rows, cols)`
- `size returns total elements`
- `slice assignment shape mismatch throws`
- `slice extracts last column with negative index`
- `slice extracts last row with negative index`
- `softmax handles large values without overflow`
- `sort axis=0 sorts each column`
- `sort axis=1 sorts each row`
- `sort no axis flattens and sorts`
- `sqrt of Float matrix`
- `sqrt of matrix`
- `subtraction rejects mismatched shapes`
- `svd: U is orthonormal (U^T ~@ U = I)`
- `svd: U ~@ diag(s) ~@ Vt = original matrix`
- `svd: Vt is orthonormal (Vt ~@ Vt^T = I)`
- `tile 1x1 returns equivalent matrix`
- `toColVec of 2D matrix flattens then reshapes`
- `toColVec reshapes to n×1`
- `toRowVec does not share data with original`
- `toRowVec reshapes to 1×n`
- `toRowVec then toColVec round trips shape`
- `transpose swaps rows and cols`
- `unique returns sorted distinct values`
- `vec.asMat returns argument`
- `where preserves shape`
- `where shape mismatch throws`
- `where with Int scalars`
- `where with Mat arguments selects elementwise`
- `zero-copy reshape`
- `zeros with shape tuple`
- `zeros with shape tuple`
- `zerosLike does not share data with original`
- `zerosLike has same shape and all zeros`

## Utilities

- `Zero-copy slicing should reflect updates to the parent`
- `applyAlongAxis axis=0 applies fn to each column`
- `applyAlongAxis axis=0 consistent with sum(0)`
- `applyAlongAxis axis=0 with max`
- `applyAlongAxis axis=1 applies fn to each row`
- `applyAlongAxis axis=1 consistent with sum(1)`
- `applyAlongAxis axis=1 with mean`
- `applyAlongAxis invalid axis throws`
- `copy creates deep copy`
- `fullLike has same shape and correct value`
- `map applies function to each element`
- `onesLike has same shape and all ones`
- `toColVec of 2D matrix flattens then reshapes`
- `toColVec reshapes to n×1`
- `toRowVec does not share data with original`
- `toRowVec reshapes to 1×n`
- `toRowVec then toColVec round trips shape`
- `zero-copy reshape`
- `zerosLike does not share data with original`
- `zerosLike has same shape and all zeros`

## Manipulation

- `Mat.normal different parameters produce different distributions`
- `concatenate axis=0 same as vstack`
- `concatenate axis=1 same as hstack`
- `diff axis=0 differences down rows`
- `diff axis=0 requires at least 2 rows`
- `diff axis=1 differences across cols`
- `diff axis=1 requires at least 2 cols`
- `diff no axis flattens and differences`
- `diff of constant matrix is zeros`
- `dropout with different seeds produces different masks`
- `hstack mismatched rows throws`
- `hstack two matrices`
- `maximum/minimum work with different types`
- `meshgrid 1x1 produces 1x1 matrices`
- `meshgrid produces correct shapes`
- `meshgrid works with col vectors`
- `meshgrid xx repeats x along rows`
- `meshgrid yy repeats y along cols`
- `percentile 0 is min`
- `percentile 100 is max`
- `percentile 50 is median`
- `percentile axis=0 gives column percentiles`
- `percentile axis=1 gives row percentiles`
- `percentile out of range throws`
- `rand Different seeds produce different sequences`
- `rand different seeds give different results`
- `repeat axis=0 repeats each row`
- `repeat axis=1 repeats each col`
- `repeat n=1 returns equivalent matrix`
- `repeat no axis repeats each element`
- `tile 1x1 returns equivalent matrix`
- `tile 1x3 triples cols`
- `tile 2x1 doubles rows`
- `tile 2x2 tiles in both directions`
- `trunc differs from floor for negative numbers`
- `vstack mismatched cols throws`
- `vstack three matrices`
- `vstack two matrices`

## Advanced

- `argsort axis=0 returns indices that would sort each column`
- `argsort axis=1 returns indices that would sort each row`
- `argsort no axis returns flat sort indices`
- `convolve full mode`
- `convolve same mode`
- `convolve unknown mode throws`
- `convolve valid mode`
- `convolve with identity filter`
- `corrcoef of perfectly anti-correlated variables is -1`
- `corrcoef of perfectly correlated variables is 1`
- `correlate full mode`
- `correlate of signal with itself peaks at center`
- `correlate valid mode default`
- `cumsum axis=0 accumulates down rows`
- `cumsum axis=1 accumulates across cols`
- `cumsum invalid axis throws`
- `cumsum no axis flattens and accumulates`
- `matrixRank of full rank rectangular matrix`
- `matrixRank of identity matrix is n`
- `matrixRank of singular matrix is less than n`
- `matrixRank of zero matrix is 0`
- `matrixRank with custom tolerance`
- `median of sorted vector`
- `nanToNum leaves finite values unchanged`
- `nanToNum replaces NaN with 0 by default`
- `nanToNum replaces negative infinity`
- `nanToNum replaces positive infinity`
- `nanToNum then isfinite all true`
- `nanToNum with custom replacement values`
- `outer product of row vectors`
- `outer product of two vectors`
- `polyfit degree too high throws`
- `polyfit linear fit through exact points`
- `polyfit quadratic fit through exact points`
- `polyfit then polyval reproduces original points`
- `polyfit x y length mismatch throws`
- `polyval constant polynomial`
- `polyval evaluates linear polynomial`
- `polyval evaluates quadratic polynomial`
- `sort axis=0 sorts each column`
- `sort axis=1 sorts each row`
- `sort no axis flattens and sorts`
- `unique counts are correct`
- `unique of matrix with all same values`
- `unique returns sorted distinct values`

## Creation

- `Frobenius norm of identity matrix is sqrt(n)`
- `Frobenius norm of known matrix`
- `Layout Guard: transpose of a slice should be auto-normalized`
- `Mat(()) treats Unit as empty matrix`
- `Mat(single tuple) creates row vector`
- `Mat.normal with mean=0, std=1 matches randn`
- `NumPy equivalence: zeros`
- `abs of matrix`
- `addition of matrices`
- `all and any work with single element`
- `arange creates sequential column vector`
- `arange rejects stop <= start`
- `arange rejects zero step`
- `arange with start and stop`
- `arange with step`
- `argmax returns index of maximum`
- `argmin returns index of minimum`
- `boolean mask indexing with no matches returns empty`
- `cholesky of identity is identity`
- `cholesky of known matrix`
- `column slice rejects out of bounds`
- `convolve full mode`
- `corrcoef diagonal is all ones`
- `corrcoef of perfectly anti-correlated variables is -1`
- `corrcoef of perfectly correlated variables is 1`
- `correlate full mode`
- `correlate of signal with itself peaks at center`
- `cov of single variable is variance`
- `cov of two variables is 2x2`
- `cross product of col vectors`
- `cross product of parallel vectors is zero`
- `cross product of standard basis vectors`
- `determinant of 1x1 matrix`
- `determinant of 2x2 matrix`
- `determinant of 3x3 matrix`
- `determinant of identity matrix is 1`
- `determinant of singular matrix throws`
- `diag from array produces diagonal matrix`
- `diag from column vector Mat`
- `diag from non-vector Mat throws`
- `diag from row vector Mat`
- `diag off-diagonal elements are zero`
- `diag rectangular nRows < nCols`
- `diag rectangular nRows > nCols`
- `diagonal of diag matrix recovers original values`
- `diagonal of non-square matrix takes min dimension`
- `diagonal of square matrix`
- `diagonal of transposed matrix`
- `diff of constant matrix is zeros`
- `dropout zeros approximately p fraction of elements`
- `eig of 2x2 known matrix`
- `eig of diagonal matrix has eigenvalues on diagonal`
- `eig of identity matrix has all eigenvalues 1`
- `eigenvalues of 2x2 symmetric matrix`
- `eigenvalues of diagonal matrix equal diagonal entries`
- `eigenvalues of identity matrix are all 1`
- `empty creates 0x0 matrix`
- `exp of zeros matrix gives ones`
- `eye creates identity matrix`
- `eye k beyond bounds is all zeros`
- `eye k=-1 is subdiagonal`
- `eye k=0 is identity`
- `eye k=1 is superdiagonal`
- `eye k=n-1 has single element in top right`
- `fancy col indexing out of bounds throws`
- `fancy indexing preserves order of indices`
- `fancy row indexing out of bounds throws`
- `fromSeq creates column vector`
- `fromSeq handles empty sequence`
- `full creates matrix with specified value`
- `full with shape tuple`
- `fullLike has same shape and correct value`
- `inverse of 2x2 matrix`
- `inverse of 3x3 matrix: m ~@ inv = I`
- `inverse of identity is identity`
- `inverse of singular matrix throws`
- `isEmpty detects empty matrix`
- `isfinite is complement of isnan and isinf`
- `kron with identity is block diagonal`
- `linspace creates evenly spaced column vector`
- `linspace creates evenly spaced column vector for Big type`
- `linspace creates evenly spaced column vector for Float type`
- `linspace defaults to 50 points`
- `linspace rejects num <= 0`
- `linspace with num=1 returns single value`
- `log of ones gives zeros`
- `logSoftmax is numerically stable`
- `lstsq rank of full rank matrix`
- `matrixRank of full rank rectangular matrix`
- `matrixRank of identity matrix is n`
- `matrixRank of singular matrix is less than n`
- `matrixRank of zero matrix is 0`
- `median of even-length vector interpolates`
- `median of sorted vector`
- `norm of Float vector`
- `norm of column vector`
- `norm of row vector`
- `norm of unit vector`
- `of creates row vector from varargs`
- `of with single value`
- `ones creates matrix filled with ones`
- `ones with shape tuple`
- `onesLike has same shape and all ones`
- `outer product of row vectors`
- `outer product of two vectors`
- `percentile out of range throws`
- `pinv of identity is identity`
- `pinv of rectangular matrix: A ~@ pinv(A) ≈ I (nRows < nCols)`
- `pinv of rectangular matrix: pinv(A) ~@ A ≈ I (nRows > nCols)`
- `pinv of singular matrix does not throw`
- `pinv of square matrix: A ~@ pinv(A) ≈ I`
- `power 0 gives all ones`
- `rand Different seeds produce different sequences`
- `rand Mat RNG matches NumPy seed=0 sequence`
- `rand Mat RNG matches NumPy seed=42 integer sequence`
- `rand Mat.rand produces values in [0, 1)`
- `rand Mat.randint generates integers in range`
- `rand Mat.randint matrix form`
- `rand Mat.randn produces standard normal distribution`
- `rand Mat.setSeed produces deterministic sequence`
- `rand Mat.uniform matches NumPy distribution range`
- `rand NumPy RNG cache file loads seeds 0-100`
- `rand different seeds give different results`
- `rand shape is correct`
- `rand values in [0, 1)`
- `rand values in [0, 1)`
- `rand with seed is reproducible`
- `rand with seed is reproducible`
- `randn shape is correct`
- `randn values roughly normal (mean near 0, std near 1)`
- `randn values roughly normal (mean near 0, std near 1)`
- `randn with seed is reproducible`
- `randn with seed is reproducible`
- `range rows single col`
- `range rows single col assignment`
- `round of integer values is unchanged`
- `row slice rejects out of bounds`
- `sign of mixed values`
- `sign of negative values is -1`
- `sign of positive values is 1`
- `sign of zero is 0`
- `single creates 1x1 matrix (workaround for Tuple1)`
- `single row range cols`
- `single row range cols assignment`
- `single with Double`
- `softmax handles large values without overflow`
- `softmax sums to 1 along axis`
- `sqrt of Float matrix`
- `sqrt of matrix`
- `subtraction of matrices`
- `svd of identity matrix has singular values all 1`
- `svd: U ~@ diag(s) ~@ Vt = original matrix`
- `tabulate creates matrix from function`
- `toColVec of 2D matrix flattens then reshapes`
- `trace of identity matrix equals n`
- `trace of non-square matrix uses min dimension`
- `trace of square matrix`
- `tril + triu - original = diagonal matrix`
- `tril k=-1 excludes diagonal`
- `tril k=0 keeps lower triangular including diagonal`
- `tril k=1 includes one superdiagonal`
- `triu k=-1 includes one subdiagonal`
- `triu k=0 keeps upper triangular including diagonal`
- `triu k=1 excludes diagonal`
- `unique of matrix with all same values`
- `zeros creates matrix filled with zeros`
- `zeros with shape tuple`
- `zeros with shape tuple`
- `zerosLike does not share data with original`
- `zerosLike has same shape and all zeros`

## Linear Algebra

- `1-norm is max absolute col sum`
- `Double ~^ cross-type promotion`
- `Frobenius norm of identity matrix is sqrt(n)`
- `Frobenius norm of known matrix`
- `Frobenius norm with negative values uses abs`
- `Layout Guard: transpose of a slice should be auto-normalized`
- `Mat.normal different parameters produce different distributions`
- `Mat.normal produces normal distribution with custom mean and std`
- `Mat.normal shape is correct`
- `Mat.normal with mean=0, std=1 matches randn`
- `R from QR is upper triangular`
- `cholesky 3x3 symmetric positive definite`
- `cholesky L ~@ L^T = original matrix`
- `cholesky non-positive-definite throws`
- `cholesky non-square throws`
- `cholesky of identity is identity`
- `cholesky of known matrix`
- `cholesky result is lower triangular`
- `corrcoef diagonal is all ones`
- `cross product is anti-commutative`
- `cross product known result`
- `cross product non-3D throws`
- `cross product of col vectors`
- `cross product of parallel vectors is zero`
- `cross product of standard basis vectors`
- `cumsum axis=1 accumulates across cols`
- `determinant of 1x1 matrix`
- `determinant of 2x2 matrix`
- `determinant of 3x3 matrix`
- `determinant of identity matrix is 1`
- `determinant of singular matrix throws`
- `determinant requires square matrix`
- `diag from array produces diagonal matrix`
- `diag off-diagonal elements are zero`
- `diagonal of diag matrix recovers original values`
- `diagonal of non-square matrix takes min dimension`
- `diagonal of square matrix`
- `diagonal of transposed matrix`
- `diff axis=1 differences across cols`
- `dot is alias for matrix multiplication`
- `eig eigenvectors satisfy A*v = lambda*v`
- `eig imaginary parts are zero for symmetric matrix`
- `eig non-square throws`
- `eig of 2x2 known matrix`
- `eig of diagonal matrix has eigenvalues on diagonal`
- `eig of identity matrix has all eigenvalues 1`
- `eigenvalues of 2x2 symmetric matrix`
- `eigenvalues of diagonal matrix equal diagonal entries`
- `eigenvalues of identity matrix are all 1`
- `eigenvalues requires square matrix`
- `eye k=-1 is subdiagonal`
- `eye k=1 is superdiagonal`
- `infinity norm is max absolute row sum`
- `inverse of 2x2 matrix`
- `inverse of 3x3 matrix: m ~@ inv = I`
- `inverse of identity is identity`
- `inverse of singular matrix throws`
- `inverse requires square matrix`
- `kron is associative`
- `kron known result`
- `kron shape is correct`
- `kron with identity is block diagonal`
- `kron with scalar matrix scales`
- `log and exp are inverses`
- `lstsq A*x approximates b for overdetermined system`
- `lstsq exact solution when square non-singular`
- `lstsq overdetermined system minimizes residuals`
- `lstsq rank of full rank matrix`
- `lstsq singular values are non-negative`
- `matrix multiplication`
- `matrix multiplication rejects invalid dimensions`
- `matrix multiplication with non-square`
- `norm of Float vector`
- `norm of column vector`
- `norm of row vector`
- `norm of unit vector`
- `norm requires vector`
- `pinv double application: pinv(pinv(A)) ≈ A`
- `pinv of identity is identity`
- `pinv of rectangular matrix: A ~@ pinv(A) ≈ I (nRows < nCols)`
- `pinv of rectangular matrix: pinv(A) ~@ A ≈ I (nRows > nCols)`
- `pinv of singular matrix does not throw`
- `pinv of square matrix: A ~@ pinv(A) ≈ I`
- `power consistent with sqrt`
- `qr decomposition square matrix: Q ~@ R = original`
- `qr decomposition: Q is orthonormal (Q^T ~@ Q = I)`
- `qr decomposition: Q ~@ R = original matrix`
- `qr decomposition: R is upper triangular`
- `rand Mat.randn produces standard normal distribution`
- `randn values roughly normal (mean near 0, std near 1)`
- `randn values roughly normal (mean near 0, std near 1)`
- `solve identity system`
- `solve multiple RHS columns`
- `solve simple 2x2 system`
- `solve singular matrix throws`
- `solve: A ~@ x = b gives b`
- `sqrt of Float matrix`
- `sqrt of matrix`
- `svd of identity matrix has singular values all 1`
- `svd singular values are non-negative and descending`
- `svd: U is orthonormal (U^T ~@ U = I)`
- `svd: U ~@ diag(s) ~@ Vt = original matrix`
- `svd: Vt is orthonormal (Vt ~@ Vt^T = I)`
- `trace of identity matrix equals n`
- `trace of non-square matrix uses min dimension`
- `trace of square matrix`
- `tril + triu - original = diagonal matrix`
- `tril k=-1 excludes diagonal`
- `tril k=0 keeps lower triangular including diagonal`
- `tril k=1 includes one superdiagonal`
- `triu k=-1 includes one subdiagonal`
- `triu k=0 keeps upper triangular including diagonal`
- `triu k=1 excludes diagonal`
- `unsupported norm ord throws`

## Random

- `Layout Guard: transpose of a slice should be auto-normalized`
- `Mat.normal different parameters produce different distributions`
- `Mat.normal produces normal distribution with custom mean and std`
- `Mat.normal shape is correct`
- `Mat.normal with mean=0, std=1 matches randn`
- `NumPyRNG matches NumPy seed=42 sequence`
- `NumPyRNG state advances correctly`
- `NumPyRNG uniform distribution with seed=42`
- `qr decomposition: Q is orthonormal (Q^T ~@ Q = I)`
- `rand Different seeds produce different sequences`
- `rand Mat RNG matches NumPy seed=0 sequence`
- `rand Mat RNG matches NumPy seed=42 integer sequence`
- `rand Mat.rand produces values in [0, 1)`
- `rand Mat.randint generates integers in range`
- `rand Mat.randint matrix form`
- `rand Mat.randn produces standard normal distribution`
- `rand Mat.setSeed produces deterministic sequence`
- `rand Mat.uniform matches NumPy distribution range`
- `rand NumPy RNG cache file loads seeds 0-100`
- `rand different seeds give different results`
- `rand shape is correct`
- `rand values in [0, 1)`
- `rand values in [0, 1)`
- `rand with seed is reproducible`
- `rand with seed is reproducible`
- `randn shape is correct`
- `randn values roughly normal (mean near 0, std near 1)`
- `randn values roughly normal (mean near 0, std near 1)`
- `randn with seed is reproducible`
- `randn with seed is reproducible`
- `svd: U is orthonormal (U^T ~@ U = I)`
- `svd: Vt is orthonormal (Vt ~@ Vt^T = I)`

## Statistics

- `1-norm is max absolute col sum`
- `Mat.normal produces normal distribution with custom mean and std`
- `Mat.normal with mean=0, std=1 matches randn`
- `applyAlongAxis axis=0 consistent with sum(0)`
- `applyAlongAxis axis=0 with max`
- `applyAlongAxis axis=1 consistent with sum(1)`
- `applyAlongAxis axis=1 with mean`
- `argmax returns index of maximum`
- `argmin returns index of minimum`
- `corrcoef diagonal is all ones`
- `corrcoef is symmetric`
- `corrcoef of perfectly anti-correlated variables is -1`
- `corrcoef of perfectly correlated variables is 1`
- `cov matrix is symmetric`
- `cov of single variable is variance`
- `cov of two variables is 2x2`
- `cov requires at least 2 observations`
- `cumsum axis=0 accumulates down rows`
- `cumsum axis=1 accumulates across cols`
- `cumsum invalid axis throws`
- `cumsum no axis flattens and accumulates`
- `determinant of 1x1 matrix`
- `determinant of 2x2 matrix`
- `determinant of 3x3 matrix`
- `determinant of identity matrix is 1`
- `determinant of singular matrix throws`
- `determinant requires square matrix`
- `diagonal of diag matrix recovers original values`
- `diagonal of non-square matrix takes min dimension`
- `infinity norm is max absolute row sum`
- `logSoftmax is numerically stable`
- `lstsq A*x approximates b for overdetermined system`
- `lstsq overdetermined system minimizes residuals`
- `max axis=0 gives column maxima`
- `max axis=1 gives row maxima`
- `max finds maximum element`
- `maximum element-wise between two matrices`
- `maximum equals relu for maximum(m, 0)`
- `maximum with scalar - ReLU pattern`
- `maximum/minimum shape mismatch throws`
- `maximum/minimum work with different types`
- `maximum/minimum work with negative numbers`
- `mean axis=0 gives column means`
- `mean axis=1 gives row means`
- `mean computes average`
- `median axis=0`
- `median of even-length vector interpolates`
- `median of sorted vector`
- `min axis=0 gives column minima`
- `min axis=1 gives row minima`
- `min finds minimum element`
- `minimum element-wise between two matrices`
- `minimum with scalar - clipping`
- `percentile 0 is min`
- `percentile 100 is max`
- `percentile 50 is median`
- `percentile axis=0 gives column percentiles`
- `percentile axis=1 gives row percentiles`
- `percentile out of range throws`
- `rand Mat.setSeed produces deterministic sequence`
- `randn values roughly normal (mean near 0, std near 1)`
- `randn values roughly normal (mean near 0, std near 1)`
- `softmax handles large values without overflow`
- `softmax sums to 1 along axis`
- `sum axis=0 gives column sums`
- `sum axis=1 gives row sums`
- `sum computes total`
- `sum invalid axis throws`
- `trace of non-square matrix uses min dimension`

## Broadcasting

- `Broadcasting Addition: 3x3 + 3x1`
- `Broadcasting: column vector should behave like a matrix`
- `addToEachCol adds col vector to every col`
- `addToEachCol wrong size throws`
- `addToEachRow adds row vector to every row`
- `addToEachRow wrong size throws`
- `broadcasting arithmetic: centering columns`
- `broadcasting preserves shape`
- `broadcasting: division by scalar (1x1 matrix)`
- `broadcasting: matrix / column vector`
- `broadcasting: matrix / row vector`
- `broadcasting: row vector / column vector`
- `mulEachCol multiplies each col by col vector`
- `mulEachRow multiplies each row by row vector`

## Element-wise Math

- `1-norm is max absolute col sum`
- `Big ~^ fractional exponent falls back to Double`
- `Big ~^ integer exponent uses BigDecimal precision`
- `Frobenius norm of identity matrix is sqrt(n)`
- `Frobenius norm with negative values uses abs`
- `Mat(single tuple) creates row vector`
- `Mat[Double] ~^ negative and fractional exponents`
- `abs leaves positive values unchanged`
- `abs of matrix`
- `all and any work with single element`
- `all rows range cols Mat assignment`
- `all rows range cols scalar assignment`
- `arctan2 computes 2-argument arctangent`
- `boolean mask assignment sets matching elements`
- `boolean mask assignment with :== `
- `clip values within range`
- `clip with equal bounds gives constant matrix`
- `correlate of signal with itself peaks at center`
- `cos computes element-wise cosine`
- `cov of single variable is variance`
- `cross product of standard basis vectors`
- `determinant of singular matrix throws`
- `diag rectangular nRows < nCols`
- `diag rectangular nRows > nCols`
- `diff of constant matrix is zeros`
- `dropout maintains expected value`
- `exp of zeros matrix gives ones`
- `eye k=n-1 has single element in top right`
- `floor and ceil work correctly`
- `infinity norm is max absolute row sum`
- `inverse of singular matrix throws`
- `isfinite is complement of isnan and isinf`
- `isinf detects infinite values`
- `linspace with num=1 returns single value`
- `log and exp are inverses`
- `log of ones gives zeros`
- `log10 and log2 handle non-powers`
- `log10 computes base-10 logarithm`
- `log2 computes base-2 logarithm`
- `logSoftmax is numerically stable`
- `lstsq exact solution when square non-singular`
- `lstsq singular values are non-negative`
- `matrixRank of full rank rectangular matrix`
- `matrixRank of singular matrix is less than n`
- `minimum with scalar - clipping`
- `mutation: column slice using =`
- `mutation: row slice using =`
- `of with single value`
- `pinv of rectangular matrix: A ~@ pinv(A) ≈ I (nRows < nCols)`
- `pinv of rectangular matrix: pinv(A) ~@ A ≈ I (nRows > nCols)`
- `pinv of singular matrix does not throw`
- `polyval constant polynomial`
- `power 0 gives all ones`
- `power 1 gives original`
- `power consistent with sqrt`
- `power negative Int throws`
- `power with Double exponent`
- `power with Int exponent 2`
- `rand Mat.randn produces standard normal distribution`
- `range rows all cols Mat assignment`
- `range rows all cols scalar assignment`
- `range rows range cols Mat assignment`
- `range rows range cols scalar assignment`
- `range rows single col`
- `range rows single col assignment`
- `rectangular slicing`
- `round negative decimals rounds to tens`
- `round of integer values is unchanged`
- `round to 0 decimals`
- `round to 2 decimals`
- `sign of mixed values`
- `sign of negative values is -1`
- `sign of positive values is 1`
- `sign of zero is 0`
- `sin computes element-wise sine`
- `single creates 1x1 matrix (workaround for Tuple1)`
- `single row range cols`
- `single row range cols assignment`
- `single with Double`
- `slice assignment shape mismatch throws`
- `solve singular matrix throws`
- `sqrt of Float matrix`
- `sqrt of matrix`
- `step range assignment`
- `svd of identity matrix has singular values all 1`
- `svd singular values are non-negative and descending`
- `tan computes element-wise tangent`
- `tanh computes element-wise hyperbolic tangent`
- `toRowVec then toColVec round trips shape`
- `trunc differs from floor for negative numbers`
- `trunc truncates toward zero`

## NumPy Compatibility

Tests demonstrating NumPy compatibility:

- NumPy-like behavior: Int literals, Double operations
- NumPy equivalence: zeros
- NumPy equivalence: array creation
- NumPy equivalence: indexing
- NumPy equivalence: slicing
- NumPy equivalence: transpose
- NumPy equivalence: matrix operations
- rand Mat RNG matches NumPy seed=42 integer sequence
- rand Mat RNG matches NumPy seed=0 sequence
- rand Mat.uniform matches NumPy distribution range
- rand NumPy RNG cache file loads seeds 0-100
- NumPyRNG matches NumPy seed=42 sequence
- NumPyRNG uniform distribution with seed=42
- NumPyRNG state advances correctly
- histogram matches NumPy seed=42

## Test Coverage

Total tests: 524
- Arithmetic: 52 tests
- Comparison: 39 tests
- Boolean: 40 tests
- Indexing: 48 tests
- ML Functions: 18 tests
- Shape: 180 tests
- Utilities: 20 tests
- Manipulation: 38 tests
- Advanced: 45 tests
- Creation: 170 tests
- Linear Algebra: 114 tests
- Random: 32 tests
- Statistics: 69 tests
- Broadcasting: 14 tests
- Element-wise Math: 91 tests
# Mat API Reference

*Auto-generated from test suite*


## NumPy Compatibility

Tests demonstrating NumPy compatibility:


## Test Coverage

Total tests: 0
# Mat API Reference

*Auto-generated from test suite*


## Shape

- `diagnostic: first raw values seed=0`
- `randint matches NumPy 2.4.0 PCG64DXSM for seed=42`

## Creation

- `diagnostic: first raw values seed=0`
- `rand matches NumPy 2.4.0 PCG64DXSM for seed=0`
- `rand matches NumPy 2.4.0 PCG64DXSM for seed=42`
- `rand matrix matches NumPy 2.4.0 PCG64DXSM for seed=12345`
- `randint matches NumPy 2.4.0 PCG64DXSM for seed=42`
- `randn matches NumPy 2.4.0 PCG64DXSM for seed=0`
- `randn matches NumPy 2.4.0 PCG64DXSM for seed=42`

## Random

- `rand matches NumPy 2.4.0 PCG64DXSM for seed=0`
- `rand matches NumPy 2.4.0 PCG64DXSM for seed=42`
- `rand matrix matches NumPy 2.4.0 PCG64DXSM for seed=12345`
- `randint matches NumPy 2.4.0 PCG64DXSM for seed=42`
- `randn matches NumPy 2.4.0 PCG64DXSM for seed=0`
- `randn matches NumPy 2.4.0 PCG64DXSM for seed=42`
- `uniform matches NumPy 2.4.0 PCG64DXSM for seed=42`

## NumPy Compatibility

Tests demonstrating NumPy compatibility:

- rand matches NumPy 2.4.0 PCG64DXSM for seed=42
- rand matches NumPy 2.4.0 PCG64DXSM for seed=0
- rand matrix matches NumPy 2.4.0 PCG64DXSM for seed=12345
- uniform matches NumPy 2.4.0 PCG64DXSM for seed=42
- randn matches NumPy 2.4.0 PCG64DXSM for seed=42
- randn matches NumPy 2.4.0 PCG64DXSM for seed=0
- randint matches NumPy 2.4.0 PCG64DXSM for seed=42

## Test Coverage

Total tests: 8
- Shape: 2 tests
- Creation: 7 tests
- Random: 7 tests
# Mat API Reference

*Auto-generated from test suite*


## Shape

- `Mat(()) treats Unit as empty matrix`
- `argmax on transposed matrix`
- `argmin on transposed matrix`
- `element access on transposed matrix`
- `flatten on transposed matrix returns logical order`

## Creation

- `Mat(()) treats Unit as empty matrix`

## Statistics

- `argmax on transposed matrix`
- `argmin on transposed matrix`

## Element-wise Math

- `flatten on transposed matrix returns logical order`

## NumPy Compatibility

Tests demonstrating NumPy compatibility:


## Test Coverage

Total tests: 5
- Shape: 5 tests
- Creation: 1 tests
- Statistics: 2 tests
- Element-wise Math: 1 tests
