# 개별 상환 계획 버전 저장 기반 — Issue #122

이 작업은 계획 source 저장소이며 API #20–23이나 대출 상세·금리 변경을 완료하는 PR이 아니다.
[미번호 SQL 제안](sql/repayment_plan_versions_proposal.sql)은 Flyway 검색 경로 밖에 있다.
V3 실제 적용 순서를 조율한 후에만 V4 적용을 준비한다. 현재 V1/V2의 checksum/구조는 변경하지 않는다.

## 원본과 버전

V2 loans.initial_plan_snapshot은 등록 기본 계획 version1로 유지한다. 이 저장소는 그 이후의 명시적인
개별 계획을 version2부터 추가한다. 원래 기본 계획을 지우거나 추정한 과거 약정을 새로 만들지 않는다.
현재 기본 계획의 전체 시작 약정을 복원할 수 없는 과거 자료는 후속 성과 계산에서 부족으로 처리해야 한다.

각 버전은 소유자·대출·버전·효력일·입력 data_revision, mode/preference, 사용자 target_date와
저장 목표 target_payoff_date, 확정 월 추가 현금/적립 목표, 계획 시작 원금 및 계산 약정 스냅샷,
원래 요청 금액(추가 원금/월 총액/직접 적립액), 생성 시각과 선택적 전략 출처를 보존한다.
원래 입력값의 null/0과 확정된 현금 배분을 구분한다. 시작 원금/약정은 저장 시 확인한 기준이며
효력일이 미래라고 그날의 실제 잔액을 이미 관측한 것으로 주장하지 않는다. 계약 스냅샷은 기존 FinanceModels.LoanTerms이며
은행의 전체 원본 메타데이터나 실제 관측 이력을 대체하지 않는다.

mode는 MATURITY/PREFERENCE/TARGET_DATE/EXTRA_AMOUNT/MONTHLY_AMOUNT/STRATEGY다.
BULLET은 적립 목표와 원금 감소를 혼합하지 않는다. 전략 출처는 report ID/version/code를 보존하지만
V5가 없는 상태에서 가짜 리포트 FK를 추가하거나 후보 유효성 검증 완료를 주장하지 않는다.
실제 저장 API는 StrategyQueryPort·preview_version·가용액·역산/경고 등 전체 비즈니스 검증을 해야 한다.

DB는 복합 소유권 FK, 버전 유일성, 금액·타입·스냅샷 소유자/시작 잔액 제약과 append-only trigger로
저장 원본 UPDATE/DELETE를 금지한다. 정정은 새 버전이다. JSON null을 소유권 조건에 통과시키지 않는다.
실제 상환 성과·예상 완납일·수수료·observed를 이 저장소에서 임의 계산/0원 저장하지 않는다.

## 저장과 조회

append는 호출자 트랜잭션이 필수이며 사용자 잠금 → 본인 대출 잠금을 순서대로 얻는다.
활성 대출인지, 입력 revision/기대 버전 및 시작 원금이 현재 원본과 일치하는지 확인하고 다음 버전만 추가한다.
같은 기대 버전의 동시 요청은 하나만 성공한다. stale은 내부 PlanVersion.Conflict이며 후속 API coordinator가
명세의 preview/stale 오류로 변환해야 한다. 저장소 자체가 새 공개 API 오류 코드를 만들지 않는다.

이 저장소는 revision 증가, 예산 재계산, API 멱등 응답을 중복 수행하지 않는다. 실제 저장 coordinator가
공통 사용자 잠금/멱등성, 여러 대출의 append, 예산 재계산, revision1회 및 응답을 같은 트랜잭션에 묶어야 한다.
키 재사용은 기존 IdempotencyService 담당이며 같은 기대 버전의 재시도를 저장소가 성공으로 가장하지 않는다.

versions는 소유자의 실제 저장 버전을 버전 순으로 반환한다. effectiveOn은 지정 날짜 이하로 효력이
발생한 버전 중 가장 높은 버전을 반환한다. 아직 미래인 버전을 현재로 적용하지 않으며 같은 효력일은
나중 버전이 우선한다. 새 커밋의 효력일이 이전 예약 버전보다 빠르면 적용 가능한 최신 버전을 우선한다.
이 메서드는 제안 테이블만 읽는다. 빈 결과가 V2 기본 계획까지 없다는 뜻은 아니며 소비자는 두 원천을
명시적으로 결합해야 한다. PAID/DELETED가 되어도 보존된 과거 버전은 소유자에게 읽을 수 있다.

## 적용 및 통합 한계

저장소 Bean 생성 시 SQL을 실행하지 않으며 아직 아무 runtime 소비자에도 연결하지 않았다.
부분 PlanQueryPort/PlanPresentationPort를 등록하지 않아 기존 적립 조회의 V2 fallback을 깨지 않는다.
SQL proposal은 격리 PostgreSQL Testcontainer에서만 실행한다. 기존 런타임의 Flyway에는 V1/V2만 남는다.

후속 작업은 V3/V4 적용 순서·실제 DB 확인, 등록 기본 계획과 버전 원천의 조회 통합, 계획 preview/저장,
기대 revision·멱등성·예산·전략 검증, 완전한 공통 query/presentation 포트 및 API 연결이다.
월 override는 팀원2 예산 소유이며 이 테이블에 장기 목표로 복사하지 않는다.
전체 전략 버전 저장, 실제 평균/관측 증빙·진행률·과거 목표 집계, 배포/프론트 검증도 이 PR 범위가 아니다.
새 HTTP method/path는 없으므로 API 완료 개수는 Team1 12/24, 전체20/50으로 유지한다.
