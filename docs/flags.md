# Feature flags

`surefold-openfeature` reads [OpenFeature](https://openfeature.dev) flags on
virtual threads, bound once to who they are evaluated for and read in every
fork and actor below. It wraps an outside library, so it lives in
surefold-integrations, and its guide is
[feature flags](https://github.com/surefold/surefold-integrations/blob/main/docs/flags.md).
Flags per tenant from Postgres are a platform module, with their guide in
surefold-platform:
[flags per tenant](https://github.com/surefold/surefold-platform/blob/main/docs/flags-per-tenant.md).
