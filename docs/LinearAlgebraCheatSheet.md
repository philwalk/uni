# uni (0.9.0) Linear Algebra Cheat Sheet

This guide is a quick reference for the `uni` library, designed to match the ergonomics of [NumPy](https://numpy.org/doc/stable/user/numpy-for-matlab-users.html) while maintaining the performance of the JVM. For most work, use `MatD` to avoid redundant type parameters.

---

### 1. Creation & Initialization
| Operation | **uni (via MatD)** | [Breeze](https://github.com/scalanlp/breeze/wiki/Linear-Algebra-Cheat-Sheet) | [NumPy](https://numpy.org/doc/stable/user/numpy-for-matlab-users.html) |
| :--- | :--- | :--- | :--- |
| **Zeroed matrix** | `MatD.zeros(n, m)` | `DenseMatrix.zeros[Double](n,m)` | `np.zeros((n,m))` |
| **Identity matrix** | `MatD.eye(n)` | `DenseMatrix.eye[Double](n)` | `np.eye(n)` |
| **Inline creation** | `MatD((1,2), (3,4))` | `DenseMatrix((1.0, 2.0), (3.0, 4.0))` | `np.array([[1,2], [3,4]])` |
| **n element range** | `MatD.linspace(0, 1, 5)` | `linspace(0, 1, 5)` | `np.linspace(0, 1, 5)` |
| **Diagonal matrix** | `MatD.diag(Array(1,2,3))` | `diag(DenseVector(1.0, 2.0, 3.0))` | `np.diag((1,2,3))` |
| **Random (Normal)** | `MatD.randn(n, m)` | *(Requires Rand object)* | `np.random.randn(n,m)` |

---

### 2. Properties & Metadata
| Operation | **uni (Public API)** | [NumPy](https://numpy.org/doc/stable/user/numpy-for-matlab-users.html) | [Breeze](https://github.com/scalanlp/breeze/wiki/Linear-Algebra-Cheat-Sheet) |
| :--- | :--- | :--- | :--- |
| **Row Count** | `m.rows` | `m.shape[0]` | `m.rows` |
| **Column Count** | `m.cols` | `m.shape[1]` | `m.cols` |
| **Shape (Tuple)** | `m.shape` | `m.shape` | *(No direct equivalent)* |
| **Total Elements** | `m.size` | `m.size` | `m.size` |

---

### 3. Indexing & Slicing
| Operation | **uni** | [NumPy](https://numpy.org/doc/stable/user/numpy-for-matlab-users.html) | [Breeze](https://github.com/scalanlp/breeze/wiki/Linear-Algebra-Cheat-Sheet) |
| :--- | :--- | :--- | :--- |
| **Basic Access** | `m(0, 1)` | `m[0, 1]` | `m(0, 1)` |
| **Extract Row** | `m(1, ::)` | `m[1, :]` | `m(1, ::)` |
| **Extract Column** | `m(::, 2)` | `m[:, 2]` | `m(::, 2)` |
| **Boolean Mask** | `m(m gt 0.5)` | `m[m > 0.5]` | `m(m :> 0.5)` |

---

### 4. Linear Algebra Operations
| Operation | **uni (0.9.0)** | [NumPy](https://numpy.org/doc/stable/user/numpy-for-matlab-users.html) | [Breeze](https://github.com/scalanlp/breeze/wiki/Linear-Algebra-Cheat-Sheet) |
| :--- | :--- | :--- | :--- |
| **Matrix Mult** | `a ~@ b` | `a @ b` | `a * b` |
| **Elementwise \*** | `a * b` | `a * b` | `a :* b` |
| **Matrix Power** | `m ~^ 2` | `linalg.matrix_power(m, 2)` | `mpow(m, 2)` |
| **Transpose** | `m.T` | `m.T` | `m.t` |
| **Inverse** | `m.inv` | `linalg.inv(m)` | `inv(m)` |
| **Solve Ax = b** | `a.solve(b)` | `linalg.solve(a, b)` | `a \ b` |

---

### Core Concepts
* **MatD Entry Point:** Use `MatD` for an ergonomic, `Double`-specialized experience.
* **Row-Vector Convention:** `linspace` returns a row vector `(1, N)` to align with [Matlab](https://www.mathworks.com/help/matlab/ref/linspace.html) and [NumPy](https://numpy.org/doc/stable/reference/generated/numpy.linspace.html).
* **Operator Precedence:** Operators prefixed with `~` (e.g., `~@`) have higher precedence than standard arithmetic for cleaner math expressions.
* **Zero-Overhead Slicing:** Uses [Scala 3 Opaque Types](https://docs.scala-lang.org/scala3/book/types-opaque-types.html) to provide fast, non-copying views of data via strides.
