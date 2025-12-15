# Contributing

Thank you for your interest in contributing to clj-artnet. This document provides guidelines for contributing to the
project.

Please read our [code of conduct](CODE_OF_CONDUCT.md) before participating.

## Ways to contribute

- **Report bugs.** Open an [issue](https://github.com/robinlahtinen/clj-artnet/issues) with a minimal reproduction.
- **Request features.** Describe your use case and how the feature would help.
- **Improve documentation.** Fix typos, clarify explanations, or add examples.
- **Submit code.** Bug fixes, new features, or performance improvements.

## Getting started

1. Fork the repository on GitHub.
2. Clone your fork locally.
3. Ensure you have:
    - [Clojure 1.12.4+](https://clojure.org/guides/getting_started)
    - Java 21+ (for Virtual Threads)

## Making changes

1. Create a topic branch from `main`.
2. Make your changes following the [coding guidelines](#coding-guidelines).
3. Write or update tests for your changes.
4. Ensure all tests pass: `clojure -T:build test`.
5. Commit with a [good commit message](#commit-messages).
6. Push to your fork and open a pull request.

## Coding guidelines

**Do** follow [the Clojure Style Guide](https://github.com/bbatsov/clojure-style-guide).

**Do** follow the [Art-Net 4 protocol specification](https://art-net.org.uk/) when implementing protocol features.

**Do** prefer pure functions and immutable data structures.

**Do** use type hints on hot-path functions to avoid reflection.

**Do** document public API functions with docstrings.

**Do** maintain the functional core and imperative shell architecture.

**Don't** add dependencies unless absolutely necessary.

**Don't** include unrelated changes in your pull request.

**Don't** overuse vertical whitespace. Avoid multiple sequential blank lines.

**Don't** add docstrings to private vars or functions.

## Testing

Run all tests with:

```bash
clojure -T:build test
```

- Include tests for new functionality.
- Property-based tests are preferred for codec changes.
- Ensure CI checks pass before requesting review.

## Commit messages

Follow the [Conventional Commits 1.0.0](https://www.conventionalcommits.org/en/v1.0.0/) specification.

```
<type>[optional scope]: <description>

[optional body]

[optional footer(s)]
```

Common types: `feat`, `fix`, `docs`, `refactor`, `test`, `chore`.

## Pull request guidelines

**Do** include tests for your change when appropriate.

**Do** ensure that the CI checks pass.

**Do** squash commits to remove corrections irrelevant to the code history after the pull request is reviewed.

**Do** feel free to follow up with the maintainers if your pull request hasn't been responded to. Sometimes
notifications are missed.

**Don't** include more than one feature or fix in a single pull request.

**Don't** open a new pull request if changes are requested. Push to the same branch instead.

## Questions?

Open a [discussion](https://github.com/robinlahtinen/clj-artnet/discussions) or file
an [issue](https://github.com/robinlahtinen/clj-artnet/issues) if you have questions.
