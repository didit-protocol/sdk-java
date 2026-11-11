# Changelog

## [1.8.0](https://github.com/didit-protocol/sdk-java/compare/v1.7.0...v1.8.0) (2026-11-11)


### Features

* **spec:** sync API spec 2026-11-11 ([2136356](https://github.com/didit-protocol/sdk-java/commit/21363562e575314d6f19d1b622e75bb1a872e03e))


### API changes that can break typed code

* **spec:** `POST /v3/organization/{organization_id}/application/{application_id}/cases/` request body `links[].entity_type`: added (required) ([2136356](https://github.com/didit-protocol/sdk-java/commit/21363562e575314d6f19d1b622e75bb1a872e03e))
* **spec:** `POST /v3/organization/{organization_id}/application/{application_id}/cases/` request body `links[].entity_uuid`: added (required) ([2136356](https://github.com/didit-protocol/sdk-java/commit/21363562e575314d6f19d1b622e75bb1a872e03e))
* **spec:** `POST /v3/organization/{organization_id}/application/{application_id}/cases/blueprints/{blueprint_uuid}/publish/` response `content_config`: type changed from any to object ([2136356](https://github.com/didit-protocol/sdk-java/commit/21363562e575314d6f19d1b622e75bb1a872e03e))

## [1.7.0](https://github.com/didit-protocol/sdk-java/compare/v1.6.0...v1.7.0) (2026-11-11)


### Features

* **spec:** sync API spec 2026-11-11 ([804ff61](https://github.com/didit-protocol/sdk-java/commit/804ff61ceb043c8a5846eca6e0baa869f311201e))

## [1.6.0](https://github.com/didit-protocol/sdk-java/compare/v1.5.0...v1.6.0) (2026-11-11)


### Features

* **spec:** sync API spec 2026-11-11 ([cb47614](https://github.com/didit-protocol/sdk-java/commit/cb47614e7be7e1b9589d124eb9fd6ac20a7e32e3))

## [1.5.0](https://github.com/didit-protocol/sdk-java/compare/v1.4.0...v1.5.0) (2026-11-11)


### Features

* **spec:** sync API spec 2026-11-11 ([9f29c24](https://github.com/didit-protocol/sdk-java/commit/9f29c24f63876aa573c402c6a494cf37c230051b))
* **spec:** sync API spec 2026-11-11 ([15e55b4](https://github.com/didit-protocol/sdk-java/commit/15e55b49810a690840c519fb16dd427bb517969d))
* **spec:** sync API spec 2026-11-11 ([19220ae](https://github.com/didit-protocol/sdk-java/commit/19220aefab9d329ae33fe7b42208afe661a256f8))
* **spec:** sync API spec 2026-11-11 ([d290604](https://github.com/didit-protocol/sdk-java/commit/d29060448767a4195fc60a6bcbc60a49af2f75ea))
* **spec:** sync API spec 2026-11-11 ([6e4c595](https://github.com/didit-protocol/sdk-java/commit/6e4c5953127e5f67d1a8b33dd13ccab53035b350))
* **spec:** sync API spec 2026-11-11 ([52da1cf](https://github.com/didit-protocol/sdk-java/commit/52da1cfe4a68acc3d09da2ab636c617f68485666))
* **spec:** sync API spec 2026-11-11 ([b98f421](https://github.com/didit-protocol/sdk-java/commit/b98f421722860cc1fdc559e11fc8f7b65cdb7b4f))

## [1.4.0](https://github.com/didit-protocol/sdk-java/compare/v1.3.0...v1.4.0) (2026-11-09)


### Features

* **spec:** sync API spec 2026-11-09 ([a02ccc0](https://github.com/didit-protocol/sdk-java/commit/a02ccc0e6264fc64d8c90e24cc4e130fca62b5b3))

## [1.3.0](https://github.com/didit-protocol/sdk-java/compare/v1.2.0...v1.3.0) (2026-11-07)


### Features

* **spec:** sync API spec 2026-11-07 ([f58ae72](https://github.com/didit-protocol/sdk-java/commit/f58ae72b155119a00c306649133362a738c1f28f))

## [1.2.0](https://github.com/didit-protocol/sdk-java/compare/v1.1.0...v1.2.0) (2026-11-07)


### Features

* **spec:** sync API spec 2026-11-07 ([d25900d](https://github.com/didit-protocol/sdk-java/commit/d25900d399522ff743e97e41bf057bd2d0094842))

## [1.1.0](https://github.com/didit-protocol/sdk-java/compare/v1.0.1...v1.1.0) (2026-11-05)


### Features

* **spec:** sync API spec 2026-11-05 (application data location) ([3fc10b2](https://github.com/didit-protocol/sdk-java/commit/3fc10b2491f36db570bd42a38efc0f81ebfd0931))

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
