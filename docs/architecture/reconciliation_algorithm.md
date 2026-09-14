# The 3-Way Reconciliation Algorithm

---

## 1. Overview & Theoretical Foundation

To achieve deterministic bidirectional file synchronization between an Android device and a remote cloud repository, RelayFS employs a **Snapshot-Based 3-Way Reconciliation Algorithm**.

### 1.1 Why Simple 2-Way Comparison Fails
If an engine only compares the **Local File ($L$)** and the **Remote File ($R$)**:
* If a file exists on $L$ but is missing on $R$, the engine cannot determine whether:
  1. The file was newly created on $L$ (and should be uploaded to $R$), OR
  2. The file was deleted on $R$ (and should be deleted from $L$).
* Attempting 2-way sync inevitably causes deleted files to resurrect or newly created files to be deleted.

### 1.2 The Role of the Baseline ($B$)
The **Baseline Snapshot ($B$)** represents the state of each file as it existed immediately after the last successful sync cycle. By comparing both $L$ and $R$ against $B$, the engine computes independent delta vectors:
$$\Delta L = \text{diff}(L, B)$$
$$\Delta R = \text{diff}(R, B)$$

---

## 2. State & Delta Definitions

For any relative path $P$:
* **$E$ (Exists):** Whether the file is present.
* **$M$ (Modification Time):** Last modified timestamp.
* **$S$ (Size):** File size in bytes.
* **$H$ (Content Hash):** Cryptographic or fast checksum (e.g. `xxHash64`).

### 2.1 Delta Function $\text{computeDelta}(X, B)$
For an endpoint $X \in \{L, R\}$:
* **`NONE`**: $X$ exists, $B$ exists, $S_X = S_B$, and ($M_X = M_B$ or $H_X = H_B$).
* **`CREATED`**: $X$ exists, but $B$ does not exist.
* **`MODIFIED`**: $X$ exists, $B$ exists, and ($S_X \neq S_B$ or $H_X \neq H_B$).
* **`DELETED`**: $X$ does not exist, but $B$ exists.

---

## 3. The Reconciliation Decision Matrix

Combining $\Delta L$ and $\Delta R$ yields the complete state space:

| Case | $\Delta L$ | $\Delta R$ | Condition | Action Taken | Target State |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **1** | `NONE` | `NONE` | — | **No-op.** | Unchanged |
| **2** | `MODIFIED` | `NONE` | — | **Upload $L \rightarrow R$** | Update $B \leftarrow L$ |
| **3** | `NONE` | `MODIFIED` | — | **Download $R \rightarrow L$** (atomic) | Update $B \leftarrow R$ |
| **4** | `CREATED` | `DELETED`* | Never on $R$ | **Upload $L \rightarrow R$** | Insert $B \leftarrow L$ |
| **5** | `DELETED`* | `CREATED` | Never on $L$ | **Download $R \rightarrow L$** (atomic) | Insert $B \leftarrow R$ |
| **6** | `DELETED` | `NONE` | — | **Delete $R$ on cloud** | Remove from $B$ |
| **7** | `NONE` | `DELETED` | — | **Delete $L$ on local** | Remove from $B$ |
| **8** | `MODIFIED` | `MODIFIED` | $H_L = H_R$ | **False Conflict.** Both edited identically. | Update $B$ with new mtimes |
| **9** | `MODIFIED` | `MODIFIED` | $H_L \neq H_R$ | **TRUE CONFLICT.** Non-destructive branch. | Keep $L$; download $R$ as conflict copy; update $B$. |
| **10**| `MODIFIED` | `DELETED` | — | **Conflict.** User edited locally while deleted on cloud. | Re-upload $L \rightarrow R$; update $B$. |
| **11**| `DELETED` | `MODIFIED` | — | **Conflict.** User deleted locally while edited on cloud. | Re-download $R \rightarrow L$; update $B$. |

*\*Note: Cases 4 and 5 represent items that did not exist in the Baseline.*

---

## 4. Conflict Handling & Branching Specification

When a True Conflict (Case 9) occurs:
1. The local file is **never modified or deleted**. It remains at its original path:
   `/storage/emulated/0/Documents/Vault/Notes/Project.md`
2. The remote file is downloaded as a branched sibling:
   `/storage/emulated/0/Documents/Vault/Notes/Project (conflict - Cloud - 2026-09-14-1600).md`
3. The branched file is added to the Room baseline database so it is tracked as a normal file in future sync passes.
4. A record is inserted into `SyncConflictEntity`.
5. A system notification alerts the user:
   > **Sync Conflict**  
   > *Project.md was modified on both devices. A conflict copy was created for your review.*

