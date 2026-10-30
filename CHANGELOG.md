# Changelog

## [1.0.1](https://github.com/didit-protocol/sdk-java/compare/v1.0.0...v1.0.1) (2026-10-29)


### Bug Fixes

* **release:** tag releases as v1.0.0 ([#16](https://github.com/didit-protocol/sdk-java/issues/16)) ([d9b1514](https://github.com/didit-protocol/sdk-java/commit/d9b151405068070465873649f134a55d0f1466f9))


### Miscellaneous Chores

* release 1.0.1 ([cfe08da](https://github.com/didit-protocol/sdk-java/commit/cfe08da8d1d7a847ff08548aaa548484c1f2b4d3))

## [1.0.0](https://github.com/didit-protocol/sdk-java/compare/didit-java-v0.1.0...didit-java-v1.0.0) (2026-10-29)


### ⚠ BREAKING CHANGES

* **spec:** the API spec sync changes the SDK surface (nullable fields, removed fields, account.organizations.list returns a page, the session decision response is the SessionDecision component).

### Features

* add bank.account_check (POST /v3/bank/account-check/) and bank_verifications on the decision ([7b3705d](https://github.com/didit-protocol/sdk-java/commit/7b3705dd23e903778a78049633c1129d2ad7d784))
* **spec:** sync API spec 2026-10-23 ([#6](https://github.com/didit-protocol/sdk-java/issues/6)) ([7e6b5dd](https://github.com/didit-protocol/sdk-java/commit/7e6b5dd85bc424f6fc2c394778131dcb48fda276))
* **spec:** sync API spec 2026-10-27 ([#13](https://github.com/didit-protocol/sdk-java/issues/13)) ([f9c1bb4](https://github.com/didit-protocol/sdk-java/commit/f9c1bb42234e074234346e19a1df288c18c2b234))


### Miscellaneous Chores

* release 1.0.0 ([058812d](https://github.com/didit-protocol/sdk-java/commit/058812d43421cfec22a8b89e8bd9888b600d6fe6))
