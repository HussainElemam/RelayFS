# Future Ideas

This document collects optional ideas for later review. Entries are proposals, not approved implementation work or release requirements.

## Reviewed merging of conflicting text files

**Recorded:** 5 October 2026

**Status:** Deferred; the current two-copy behavior meets the present use case.

### Current behavior

Keep Local selects the local version. Keep Cloud installs the preserved cloud version locally. Keep Both preserves two separate files containing the respective local and cloud edits. Successful resolutions queue a sync for the folder pair.

The diff viewer compares the two versions; its displayed additions and deletions are not a proposed merged document. Keep Both does not combine their contents.

### Possible future experience

- Make the distinction explicit with a label such as **Save both copies**, while retaining that option.
- Add a separate **Merge changes** action for supported Markdown and plain-text files.
- Prepare a merged preview using the last shared version, the local version, and the cloud version.
- Combine edits to different regions in the preview; highlight overlapping changes for the user to choose local, cloud, or manually edited text.
- Save one merged file and queue synchronization only after the user reviews and accepts the result.
- Retain the original versions for recovery.

For example, a local paragraph addition and a cloud correction to a different paragraph can often be combined. Two different replacements of the same value require an explicit choice. Keeping every line from a two-version diff can duplicate text or restore an intentional deletion.

### Prerequisites and limits

- The current baseline stores hashes and metadata, not the last shared file contents. Reliable three-way merging would require durable, version-associated baseline content, with a storage and retention policy.
- Existing conflicts without a shared version would offer manual merging or the existing version-selection options; they would not receive an automatic merge guess.
- A merge without overlapping text changes can still be logically inconsistent. Keep a review step rather than claiming that an algorithm proves the result correct.
- Initially exclude binary files, oversized text, and structured formats requiring format-specific validation. Preserve the existing local/cloud/two-copy choices for unsupported files.
- Never write unresolved conflict markers into the synchronized original. Recheck file versions before applying a reviewed result; if either side changes during review, retain the draft and request a fresh review.
- Use the existing atomic-write, version-preservation, cancellation, and interrupted-resolution safeguards.

### Evaluation before implementation

Consider this only if duplicate conflict files become inconvenient enough to justify a merge editor and baseline storage. Validate independent edits, identical edits, overlapping replacements, deletions versus edits, empty files, encodings and line endings, unavailable baseline content, edits during review, interrupted saves, and subsequent sync convergence.

Reference: [Git's three-way file merge documentation](https://kernel.org/pub/software/scm/git/docs/git-merge-file.html).
