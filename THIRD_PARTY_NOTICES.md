# Third-party notices

## DeepSeek Harness

This project is an **unofficial companion** to
[DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) ("DSH").

DSH is licensed under the MIT License, Copyright (c) 2026 DeepSeek. Its license
permits this project's existence: the plugins here consume DSH's public plugin
API, and nothing more.

Specifically, the plugins in `plugins/`:

- are original code written for this project;
- **do not** vendor, copy, sublicense, or modify DSH source code;
- reference `@deepseek-ai/dsh-*` packages as **development-time type
  dependencies only**, and ship no DSH code in their build output.

If you distribute a build of this project, keep DSH's own MIT notice intact
alongside anything you redistribute from it.

## Not affiliated

This project is **not affiliated with, endorsed by, or sponsored by DeepSeek**.

"DeepSeek Harness" and "DSH" are used here only to describe what this project
interoperates with, in the descriptive sense permitted by trademark law. No
claim is made to those marks.

## Other dependencies

Dependency licenses are recorded by the toolchain:

- Android: `./gradlew :app:dependencies` and the Gradle license report
- Plugins: `pnpm licenses list` in `plugins/`
- `wol-bridge`: Python standard library only, no third-party dependencies

If you add a dependency with a copyleft license, say so in your pull request —
it changes what downstream users can do with this project.
