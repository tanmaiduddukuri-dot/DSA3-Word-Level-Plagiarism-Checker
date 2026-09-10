# Word-Level Plagiarism Checker for Short Documents

25CS2103E — Data Structures and Algorithms (DSA-3)

Implementation of the project proposal: a plagiarism checker that detects
both verbatim copying and paraphrased/reordered copying in short documents,
built entirely from first principles (no string-matching, hashing, or diff
libraries). It combines:

- **Rabin–Karp rolling hash** for exact k-gram (verbatim) matching, O(n + m)
  average case.
- **Longest Common Subsequence (dynamic programming)** for paraphrase and
  reorder detection, O(n · m).

## Project layout

```
PlagiarismChecker/
  src/com/plagcheck/
    Main.java                         CLI entry point (check / evaluate modes)
    PlagiarismChecker.java            Wires the pipeline stages together
    preprocessing/
      Token.java                      A single normalized word + position
      Document.java                   A tokenized document
      Tokenizer.java                  Reads a file, normalizes, tokenizes
    matching/
      MatchSpan.java                  A matched region (EXACT or PARAPHRASE)
      RabinKarpMatcher.java           Exact k-gram matching via rolling hash
      LCSAligner.java                 DP-based paraphrase/reorder detection
    scoring/
      ScoreResult.java                Overall score + per-source breakdown
      SimilarityScorer.java           Merges matches into one clean score
    reporting/
      ReportGenerator.java            Human-readable highlighted report
    evaluation/
      Evaluator.java                  Precision/recall/false-positive rate
  src-tests/
    SanityTest.java                   Dependency-free regression checks
  test-data/
    reference/                        Sample reference corpus
    submissions/                      Sample verbatim / paraphrased / original docs
    labelled/                         Sample hand-labelled evaluation set
```

## Building

Requires a JDK (11+). No external dependencies or build tool are needed.

```
mkdir -p out
javac -d out $(find src -name "*.java")
```

## Running a single check

```
java -cp out com.plagcheck.Main check \
    --submission test-data/submissions/verbatim_copy.txt \
    --reference test-data/reference \
    --k 5 --min-span 3
```

Options:

| Flag           | Meaning                                                        | Default |
|----------------|-----------------------------------------------------------------|---------|
| `--submission` | Path to the document being checked                              | —       |
| `--reference`  | Directory of reference/source documents                         | —       |
| `--k`          | Rabin–Karp window size (tokens per exact-match k-gram)           | 5       |
| `--min-span`   | Minimum aligned-token run length for a paraphrase span to count  | 3       |
| `--stopwords`  | If present, strips common stop words before comparison           | off     |

The report prints an overall similarity percentage, a breakdown of how much
each reference document contributed, the list of matched spans (with line
numbers), and the submission text with matches wrapped in `[EXACT:source]`
or `[PARA:source]` tags.

## Running the evaluation harness

```
java -cp out com.plagcheck.Main evaluate \
    --reference test-data/reference \
    --plagiarized test-data/labelled/plagiarized \
    --original test-data/labelled/original \
    --threshold 20
```

This runs every document in `--plagiarized` and `--original` through the
same pipeline, classifies each as PLAGIARIZED if its score is at or above
`--threshold`, and reports precision, recall, and false-positive rate
against the folder names as ground truth — directly implementing the
proposal's Evaluation step.

## Sample results (included test data)

| Submission             | Ground truth | Predicted score |
|-------------------------|-------------|------------------|
| `verbatim_copy.txt`      | Plagiarized | 80.65%           |
| `paraphrased_copy.txt`   | Plagiarized | 25.35% (39.44% with `--min-span 2`) |
| `original_work.txt`      | Original    | 0.00%            |
| `independent_essay.txt`  | Original    | 0.00%            |

At threshold 20%: Precision = 1.00, Recall = 1.00, False Positive Rate = 0.00
on this small demonstration set.

## Running the sanity tests

```
javac -cp out -d out src-tests/SanityTest.java
java -cp out SanityTest
```

For the final submission, replace this with proper JUnit tests per module
as listed in the proposal's Software Requirements.

## Design notes

- **Two-stage design.** Rabin–Karp is run first across the whole corpus in
  roughly linear time and catches verbatim copying cheaply. LCS is more
  expensive (quadratic) and is reserved for finding reordered/reworded
  overlap; this mirrors the two-stage MOSS-style approach identified in the
  proposal's literature survey.
- **No double counting.** A submission token can be claimed by at most one
  (source, match type) pair. Exact matches take priority over paraphrase
  matches when both cover the same token, since verbatim evidence is
  stronger. The similarity percentage is always "tokens covered / total
  tokens", never a naive sum of individual span lengths.
- **Source attribution.** Because coverage is tracked per token and per
  source document, the report can show exactly how much each reference
  document contributed to the overall score, not just a single aggregate
  number.
- **Collision safety.** Rabin–Karp hash hits are always verified with a
  direct token-by-token comparison before being accepted, so a hash
  collision can never produce a false match.
- **Tuning `--k` and `--min-span`.** A smaller `--k` catches shorter
  verbatim phrases but increases the chance of coincidental matches on
  common short phrases; a smaller `--min-span` makes the paraphrase
  detector more sensitive but noisier. The demonstration table above shows
  this trade-off directly on `paraphrased_copy.txt`.
- **Scaling.** LCS is O(n·m) in time and space, which is appropriate for
  the "short documents" scope stated in the proposal. For a larger corpus,
  the Rabin–Karp stage could be used to shortlist only the reference
  documents worth running LCS against, rather than running LCS against
  every document as this reference implementation does for simplicity.
