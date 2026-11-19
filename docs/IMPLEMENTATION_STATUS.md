> 최신 중간 점검: [2026-11-19 진행 현황](PROGRESS_2026_11_19.md). 아래 내용은 PR별 당시 기록입니다.

# Implementation audit — 2026-11-18 KST

Audited central develop at `579141fc600e3594ad3c6592b60dd2d3b89f7652`, its Git history, existing tests, and
the currently visible Team 2 Fork branches/issues/Draft PRs. A feature branch or issue describing work does
not establish that its implementation is available in central develop. The private handoff package remains local.

## Central implementation before this change

| Area | Verified repository evidence | Remaining integration |
|---|---|---|
| Common project | Bootstrap PR #1; Java 21, Boot/Gradle, V1, anonymous sessions, envelope, Origin checks, locks, revisions, idempotency | Reuse existing application and contracts |
| User/settings | GET/PATCH profile, subscription, notifications: six mapped API operations | Existing local integration tests passed |
| CSV export | PR #14, API #54 | Real LoanQueryPort absent; fail with 503 rather than fixtures |
| Risk scenario | PR #43 and subsequent refactors, API #44 | Real loan/plan/budget providers and engine absent at baseline; adapter tests used controlled inputs |
| Pure policies | Budget cash, risk math, report statistics, recommendation/input policies, strategy scope/input builders | These helpers are not completed HTTP endpoints |
| External adapters | FSS normalizer and explanation client/config | No actual external call or complete report worker demonstrated in central develop |
| Runtime packaging | Container/HTTPS Compose and CI configuration | Configuration is not production deployment |

Eight product API method/path operations are mapped in central develop. Six operate on the common user/settings
state; CSV and risk retain missing-domain dependency checks. This is not an end-to-end claim for all eight.
The original implementation target remains 50 operations; authentication/account deletion are excluded.

## Existing Team 2 work outside central develop

The Fork has separate feature branches for finance profile, onboarding, catalog, monthly budgets, income entries,
allocation, comparison settings, report storage/read/generation/worker/recovery and strategy read/calculation.
The report preparation tree at `da7dd5ad33a939fa6c0db674c9250da515d813cf` contains report controllers and V5,
which are absent from central develop. Their previously reported tests have not been rerun in this checkout.
Do not reimplement these Team 2 areas or merge their cumulative preparation branches as part of Team 1 work.

Relevant open coordination PRs at audit time:

- [#15: V3 finance/budget/catalog schema](https://github.com/LoanRun-Team/LoanRun-Backend/pull/15)
- [#29: access and monthly-plan ports](https://github.com/LoanRun-Team/LoanRun-Backend/pull/29)
- [#46: V5 report schema](https://github.com/LoanRun-Team/LoanRun-Backend/pull/46)
- [#50: history query ports](https://github.com/LoanRun-Team/LoanRun-Backend/pull/50)

These are coordination dependencies, not authorization to change shared contracts. Only V1 is in the audited
central migration directory. Keep the V2/V3/V4/V5 reservations and coordinate ordering before integrating schemas.

## Team 1 work

| Feature | Baseline | This change / next dependency |
|---|---|---|
| Pure RepaymentEngine | Interface only; no implementation | Issue #74 adds schedules, fees, savings, allocation and inverse targets through existing signatures |
| Loans / rates, API #12–19 | No controllers, services, repositories or V2 | Next: loan preview and owner-scoped persistence/query projections |
| Payments / due ledger, API #24–31 | Not implemented | Needs loans, source snapshots, frozen dues and transactional replay |
| Repayment savings, API #32–35 | Not implemented | Needs shared loan/payment savings ledger and budget recalculation |
| Plans / overall, API #20–23 | Not implemented | Needs source queries, budget capacity/overrides, strategy validation and V4 |

All 24 Team 1 HTTP operations remain pending after this engine-only change. No bootstrap, shared interface,
migration, existing endpoint, dependency or environment variable is changed. Each subsequent feature should
have its own central Issue, personal Fork branch `<type>/<issue>-<english-description>` and develop PR.

Baseline validation in this checkout: Java 21 `./gradlew clean build`, 100 tests, failures/errors/skips 0,
including PostgreSQL Testcontainers. Engine validation and exact final counts are recorded in its PR.
No frontend, Notion, Figma, production database or deployment was modified.

## Follow-up: loan preview (Issue #76)

After engine PR #75 merged at f9dcb78, API #14 POST /api/loans/preview/ and reusable LoanInputValidator were added. No V2 or actual registration API is included. The 24-operation baseline above remains historical; 23 Team 1 HTTP operations remain pending after this follow-up. Product ID lookup requires the real catalog port; manual input needs no external service. Preview leaves financial revision and idempotency records unchanged, verified with PostgreSQL and real HTTP requests.

## Follow-up: registration (Issue #79)

API #13 and V2 source storage are implemented with user locking, the active-loan cap, initial MATURITY snapshot, first due and atomic recalculation/revision. Success tests exercise the actual PostgreSQL repository and HTTP endpoint with a test-only budget provider; the normal runtime remains blocked with503 until Team2 supplies the real recalculation/query chain. No runtime placeholder provider, LoanQueryPort or PlanQueryPort is introduced. V3/V4/V5 remain unchanged; existing V3/V5 environments require coordinated migration ordering. Other Team1 HTTP operations (#12, #15–35) remain pending.

## 2026-11-19 migration review for #80

The pinned V1/V2/V3/V5 SQL combination passed five isolated PostgreSQL/Flyway11.7.2 audit cases: fresh order, V3/V5-first V2 introduction, existing-row/history preservation, schema equivalence, restart with default ordering and invalid-file validation. See MIGRATION_V2_REVIEW.md and the read-only psql script. Actual Team2 DB state has not been supplied. Central V2 review can proceed separately from that DB migration and real budget/query-port integration. No actual development/production DB, shared SQL or application setting was changed.

## Follow-up: source reads (Issue #82)

LoanSourceRepository/LoanSources add real owner/date-scoped V2 reads and immutable typed facts. PostgreSQL tests cover ownership, dates, original values and no financial writes. After #80 merged at c275347, the source-read feature was integrated with central develop and retested for a separate PR. It is not a completed LoanQueryPort; financial projection/aggregation and public loan query APIs remain pending.

## Follow-up: rate history (Issue #87)

After source-read PR #86 merged at 825c582, API #18 GET /api/loans/{loan_id}/rate-history/ adds a public,
owner-scoped read of stored manual rate history. The registration rate is included; previous_rate is null
for the first row and otherwise derived by effective_date/id order, preserving zero and duplicate values.
DELETED/foreign/missing loans return 404; PAID history remains visible. No runtime port, schema, mutation
or Team 2 code is changed. Team 1 has three HTTP operations implemented (#13, #14, #18), with #13 still
requiring the real budget recalculation provider. The other 21 Team 1 operations, LoanQueryPort financial
projections, PlanQueryPort, frontend integration and deployment remain pending. Validation counts are
recorded in the feature PR.

## Follow-up: payment detail (Issue #89)

After rate-history PR #88 merged at c179067, API #28 GET /api/payments/{payment_id}/ reads the session
owner's stored payment and all due allocations through a single owner-scoped SQL statement. Original
amounts/fees/savings use, KST timestamps, per-due delay and snapshot-date editability are preserved.
PAID/DELETED loan history remains visible; foreign/missing records return 404. This read requires no
budget/query provider and makes no financial writes. No schema/shared contract or Team 2 code changes.
Team 1 now has four HTTP operations implemented (#13, #14, #18, #28); #13 still requires the real
budget provider. The remaining 20 Team 1 operations and financial query-port/budget integration are
pending. This does not implement payment writes, frontend integration or deployment. Test results are
recorded in the feature PR.

## Follow-up: payment list (Issue #91)

Payment-detail PR #90 was verified independently complete with CI66 success and merged at 462efcc.
API #24 GET /api/payments/ reuses its item conversion and adds owner-scoped actual-date/type/loan
filters, stable paid_at/id descending pagination and full-filter count/component totals. Paginating
payment rows before allocation joins preserves all allocations without duplicated totals or shortened
pages. PAID/DELETED history is retained; foreign/missing loan filters return 404. Both reads require
no budget provider and introduce no runtime placeholder, migration, shared contract or Team 2 change.
Team 1 now has five HTTP operations implemented (#13, #14, #18, #24, #28); #13 still awaits real budget
recalculation. The other 19 operations, financial query projections and budget integration are pending
as separate features, not blockers to merging these completed read APIs. Frontend/deployment remain
unverified. Local and CI results are recorded in the feature PR.

## Follow-up: due confirmation (Issue #93)

After payment-list PR #92 merged at d1771d4, API #31 adds mandatory-key owner-scoped overdue due
confirmation/cancellation using the shared user lock and durable idempotency service. Partial/paid/zero
obligations are rejected, existing ledger/assets remain intact, and the flag/recalculation/revision/response
are atomic. PostgreSQL/HTTP success tests use only a test budget hook; normal runtime has no replacement
provider and returns 503 without persisting until real recalculation/query integration is available.
No schema, common interface, environment/dependency or Team 2 code changes. Team 1 has six operations
implemented (#13, #14, #18, #24, #28, #31), with #13/#31 integration-blocked by actual recalculation.
The other 18 operations, financial query projections and real budget integration remain pending. This
is not a frontend or deployment claim. Validation results are recorded in the feature PR.

## Follow-up: stored due states and no-change confirmation (Issue #95)

After #94 merged at 9bb9fa3, owner-scoped stored-due queries aggregate actual allocation principal,
interest and fee, preserve frozen due sources/loan status and evaluate all five current regular-due
states. #31 eligibility reuses that calculator under its existing user/due locks. New-key requests
that retain the current confirmation flag now skip recalculation/revision and return report_stale=false
(no additional invalidation), correcting the prior reconfirmation behavior to FINAL_DECISIONS.
The read model does not synthesize future dues, infer historical confirmation state or register an
incomplete LoanQueryPort. No new HTTP operation, shared port, schema or Team 2 code change. Six Team1
HTTP operations remain implemented; actual flag changes/registration still require the real budget
provider. Terms/balance replay, complete due coverage and monthly observation projections remain
needed before LoanQueryPort and actual budget/CSV/risk integration can be reported complete.

## Follow-up: actual payment aggregates (Issue #102)

After #96 merged at 87c63bf (also including Team2's risk detail presentation work), exact paid_at-based
month/loan and full-range payment aggregates separate regular/extra principal, interest, fees, savings
use, cash outflow and source counts. API #24 shares those totals without changing its public response.
No allocation joins or empty synthetic months; zero source sums do not imply observed performance.
User follow-up prompted a reachability audit: normal BULLET/savings and installment principal rules do
not require the proposed mixed-principal savings priority. It was not introduced; write API #25/#29
validation is still pending, and generic DB/read fixtures are not proof of supported input. observed
remains unselected. LoanQueryPort and real budget/CSV/risk integration remain incomplete; no new HTTP
operation, schema/shared interface or Team2 code change is included. The initial compile's missing
HttpStatus import was corrected before the successful regression runs.

## Follow-up: payment observation evidence (Issue #104)

After #103 merged at 6c55518, the user confirmed observed only for completed monthly recording or
explicit actual zero, never inferred from missing records. Payment observation now separates raw
aggregates from nullable confirmed actuals and validates owner/loan/month evidence against a canonical
payment-row snapshot. Source changes invalidate prior evidence; unrelated months do not. KST future
months/evidence and contradictory zero claims remain unobserved. No real completion source exists in
V2, so normal runtime has observed=false/actuals=null until committed evidence is connected. No default
provider, new public API, shared port or migration is added. Completion storage/input workflow,
LoanQueryPort and actual budget/report integration are not reported complete. The initial JDK Month
name conflict was corrected by naming the internal result ObservedMonth before successful verification.

## Issue #108 — API #12 보유 대출 목록

개인 브랜치에서 GET /api/loans/를 구현했다. ACTIVE/PAID/ALL, 세션 소유권, ACTIVE 합계,
확정 회차와 실제 적립금, 현재 잔액·원본 약정 메타데이터를 사용한다. 현재 계획의 예상 완납일은
근거가 없어 null이다. 전체 LoanQueryPort나 예산 재계산 통합 완료를 의미하지 않는다.
DB/공통 계약 변경은 없다. V3 이전에 V4를 적용하지 않는다.
세부 사항은 [목록 조회](LOAN_LIST.md), 병합 전후 개수는 [중간 점검](PROGRESS_2026_11_19.md)을 참조한다.

검증: JDK 21 `./gradlew clean build` 성공, 전체 304 tests / 0 failures / 0 errors / 0 skipped.
새 목록 HTTP/PostgreSQL 통합 검증 19개 포함. 배포/프론트 전체 흐름 검증은 이 결과에 포함하지 않는다.

## Issue #110 — API #32 상환자금 적립 내역

#109 병합(ad37a65) 후 개인 Fork/develop을 최신화했다. API #15는 현재 계획 객체·진행률·
예상 완납 계산 구현이 선행되어야 하므로 임의 응답을 만들지 않고, 독립 구현 가능한 #32를 진행한다.
GET /api/repayment-savings/는 본인 BULLET의 날짜 필터 기록, 전체 자산, 당월 기록 합계 및 목표를 읽는다.
대출 목록과 자산/필요 적립액 계산을 공유하며 정기 이자 선납이 적립 회차를 줄이는 문제를 보완한다.
PlanQueryPort/당월 배분이 제공될 때는 실제 제공값을 우선하며, 현재는 V2의 실제 기본 계획을 사용한다.
DB·공통 계약 변경은 없다. [상세 동작과 한계](SAVING_LIST.md)를 참조한다.

최초 검증: JDK 21 clean build 336 tests / 0 failures/errors/skipped(신규 적립 조회 32개).
추가 중앙 병합 #29/#50/#84/#101 반영 후 전체 검증 결과는 아래 후속 기록을 따른다.

최신 중앙 2cfd190 병합 후 JDK 21 `./gradlew clean build` 성공: 342 tests / 0 failures/errors/skipped.

## Issue #112 — API #33 상환자금 적립 등록

#111 및 #114 병합(6d857b0)을 개인 develop/Fork에 반영하고 feat/112-saving-create에서 구현했다.
POST /api/repayment-savings/: 활성 BULLET/소유권/기준일·금액 검증, 공통 사용자 잠금/멱등성,
대출 행 잠금, 실제 적립 저장·실제 적립 월부터 예산 재계산·revision·응답 저장을 원자 처리한다.
양수 추가만 하며 원금·이자·납부·확정 회차·계획 및 월 관측 상태는 변경하지 않는다.
실제 예산 공급자 없는 운영 정상 요청은 503/무저장이며 성공 흐름 검증은 테스트 공급자를 사용한다.
DB/공통 계약 변경 없음. [등록 동작과 의존성](SAVING_CREATE.md)을 참조한다.

검증: JDK 21 `./gradlew clean build` 성공, 전체366 tests / 0 failures/errors/skipped.
신규 등록 HTTP/DB 검증23개 + 실제 예산 공급자 없는 HTTP 503/무저장 검증1개 포함.
초기 테스트 보조 메서드 이름 충돌로 컴파일에 실패했으며 수정 후 전체 검증을 통과했다.

## Issue #116 — API #34 상환자금 적립 수정

#115 병합(da7311b)을 개인 develop/Fork에 반영했다. PATCH는 생략 유지/null 거절, 활성 BULLET·소유권·
과거 잠금, 날짜/금액 및 사용 후 잔액을 검증한다. 사용자 확인에 따라 순서 불명확한 위험 감소/지연은409다.
같은 날짜 증가/안전한 변경을 일괄 차단하지 않는다. 기존/새 월 중 이른 월부터 재계산·revision·응답을 원자 저장한다.
현재 예산 공급자가 없어 실제 변경은503/무저장, 적격 무변경은200/추가 revision 없음이다.
DB/공통 계약 변경 없음. [수정 규칙·트랜잭션·한계](SAVING_UPDATE.md) 참조.

검증: JDK 21 `./gradlew clean build` 성공, 전체399 tests / 0 failures/errors/skipped.
신규 수정 통합 검증32개 + 실제 예산 공급자 없는 변경/무변경 HTTP 검증1개 포함.

## Issue #118 — API #35 상환자금 적립 삭제

#117 병합(7efc20b)을 개인 develop/Fork에 반영했다. DELETE는 기존 수정의 소유권·활성BULLET·
과거 잠금·자산/순서 검증을 재사용한다. 대상 원본 제거·실제 적립 월부터 재계산·revision·요약/응답을
원자 처리하며 실패 시 복구한다. 삭제된 기록의 같은 키는 최초200을 재사용하고 새 키는404다.
원금·이자·회차·납부·배분·계획·초기 자산·월 관측 상태는 변경하지 않는다.
현재 예산 공급자가 없어 운영 유효 삭제는503/무삭제다. [삭제 규칙/연동 상태](SAVING_DELETE.md) 참조.
DB/공통 계약 변경 없음.

검증: JDK21 `./gradlew clean build` 성공, 전체415 tests / 0 failures/errors/skipped.
신규 삭제 통합 검증15개 + 실제 예산 공급자 없는503/무삭제 HTTP 검증1개 포함.

## Issue #120 — API #17 대출 삭제

#119 병합(9916f37)을 개인 develop/Fork에 반영했다. DELETE /api/loans/{loan_id}/는 본인 소유권과
실제 납부 유무를 검사하여 납부가 있으면 명세대로409 LOAN_HAS_PAYMENTS, 그 외DELETED로 보존한다.
원금/금리·잔액 이력·적립·회차·기본 계획을 보존하며 활성 목록/한도에서 제외한다. 선택 키와
사용자/대출 잠금, 현재월 예산 재계산·revision·응답을 원자 처리한다. 실제 예산 공급자 없으면503/무변경이다.
DB/공통 계약 변경 없음. [삭제 규칙 및 실제 연동 한계](LOAN_DELETE.md) 참조.

검증: JDK21 `./gradlew clean build` 성공, 전체433 tests / 0 failures/errors/skipped.
신규 대출 삭제 통합17개 + 실제 예산 공급자 없는503/상태 보존 HTTP1개 포함.

## Issue #122 — 개별 계획 버전 저장 기반

#121 병합(5befcba) 이후 계획 객체·진행률·금리 변경이 공통으로 필요로 하는 저장 원천을 준비한다.
V2 기본version1을 유지하며 이후 목표/입력금액·효력일·입력revision·시작 약정/원금·전략 출처를
불변version2+로 보존한다. 기대버전/입력revision과 소유권·사용자/대출잠금, 여러대출 원자성을 검증한다.
미번호SQL proposal만 추가하며 V3보다 V4를 먼저 적용하지 않는다. 실제 소비 API/부분 query Bean 없음.
새 HTTP API 개수는 추가하지 않는다. [저장 기반과 통합 한계](PLAN_VERSION_STORAGE.md) 참조.

검증: JDK21 `./gradlew clean build` 성공, 전체450 tests / 0 failures/errors/skipped(신규 저장소17).
최초 검증은 Spring의 인자 예외 변환을 테스트가 고려하지 못해1건 실패했고, 실제 구조에 맞춰 수정 후 통과했다.

## Issue #124 — 계획 원천 이력 조회 조합

#123 병합(a46b592)을 개인 develop/Fork에 반영했다. 내부 조회는 V2등록 기본 계획과 새 버전을
적용일/확인시점으로 조합한다. 현재 원금·약정으로 과거를 채우지 않고 소유권·보존이력을 지킨다.
V4없음은 빈이력이 아니라503이며 명시적V2-only조회와 구분한다. DB/공통포트/기존API 변경 없음.
[원천 조합·조회 한계](PLAN_SOURCE_QUERY.md) 참조. 새 endpoint/API 완료 개수 증가는 없다.

검증: JDK21 `./gradlew clean build` 성공, 전체459 tests / 0 failures/errors/skipped(신규9).
