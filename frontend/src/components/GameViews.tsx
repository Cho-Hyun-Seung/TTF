import type {
  LeaderboardEntry,
  TtfGameSnapshot,
  RoundResult,
  RoundSnapshot,
  VisibleStatement,
} from '../domain/types'
import { useCountdown } from '../hooks/useCountdown'
import { CheckIcon, CrownIcon, UsersIcon } from './Icons'

export function ResultRevealNotice() {
  return (
    <div className="result-reveal-notice" role="status">
      <span className="closed-symbol" aria-hidden="true">✓</span>
      <p className="eyebrow">모두 투표했어요!</p>
      <h2>잠시 후 결과가 공개돼요</h2>
      <p>과연 가짜는 무엇이었을까요?</p>
      <div className="waiting-dots" aria-hidden="true"><span /><span /><span /></div>
    </div>
  )
}

export function RoundHeading({ round }: { round: RoundSnapshot }) {
  return (
    <div className="round-heading">
      <div className="round-count" aria-label={`${round.total}개 라운드 중 ${round.number}번째`}>
        <span>{String(round.number).padStart(2, '0')}</span>
        <span>/ {String(round.total).padStart(2, '0')}</span>
      </div>
      <div>
        <p className="round-topic">주제 · {round.topic.title}</p>
        <h2>{round.speaker.nickname}</h2>
        <small>이번 이야기의 주인공</small>
      </div>
    </div>
  )
}

export function StatementCards({
  statements,
  selectedId,
  disabled = false,
  onSelect,
}: {
  statements: VisibleStatement[]
  selectedId?: string
  disabled?: boolean
  onSelect?: (statementId: string) => void
}) {
  return (
    <div className="statement-list" role={onSelect ? 'radiogroup' : 'list'} aria-label="공개된 문장">
      {statements.map((statement, index) => {
        const selected = statement.id === selectedId
        if (!onSelect) {
          return (
            <article className="statement-card" key={statement.id} role="listitem">
              <span className="statement-card__number">{index + 1}</span>
              <p>{statement.content}</p>
            </article>
          )
        }
        return (
          <button
            aria-checked={selected}
            className={`statement-card statement-card--button${selected ? ' is-selected' : ''}`}
            disabled={disabled}
            key={statement.id}
            onClick={() => onSelect(statement.id)}
            role="radio"
            type="button"
          >
            <span className="statement-card__number">{index + 1}</span>
            <span className="statement-card__copy">{statement.content}</span>
            <span className="statement-card__check" aria-hidden="true"><CheckIcon /></span>
          </button>
        )
      })}
    </div>
  )
}

export function Countdown({
  endsAt,
  serverTime,
  clientReceivedAt,
  large = false,
}: {
  endsAt?: string
  serverTime?: string
  clientReceivedAt?: number
  large?: boolean
}) {
  const countdown = useCountdown(endsAt, serverTime, clientReceivedAt)
  return (
    <div
      className={`countdown${large ? ' countdown--large' : ''}${countdown.urgent ? ' is-urgent' : ''}`}
      role="timer"
      aria-label={`${countdown.seconds}초 남음`}
    >
      <span className="countdown__label">남은 시간</span>
      <strong>{countdown.label}</strong>
    </div>
  )
}

export function VoteProgress({ completed, eligible }: { completed: number; eligible: number }) {
  const rate = eligible === 0 ? 0 : Math.min(100, Math.round((completed / eligible) * 100))
  return (
    <div className="vote-progress">
      <div className="vote-progress__copy">
        <span><UsersIcon /> 투표 현황</span>
        <strong>{completed} / {eligible}명</strong>
      </div>
      <div className="progress-track" aria-label={`투표 ${rate}% 완료`} role="progressbar" aria-valuemin={0} aria-valuemax={100} aria-valuenow={rate}>
        <span style={{ width: `${rate}%` }} />
      </div>
    </div>
  )
}

export function ResultPanel({ result }: { result: RoundResult }) {
  return (
    <section className="result-panel" aria-labelledby="result-title">
      <div className="section-heading">
        <p className="eyebrow">정답 공개</p>
        <h2 id="result-title">가짜는 이 문장이었어요</h2>
      </div>
      <div className="result-list">
        {result.statements.map((statement, index) => (
          <article className={`result-card${statement.is_fake ? ' result-card--fake' : ''}`} key={statement.id}>
            <div className="result-card__top">
              <span className="statement-card__number">{index + 1}</span>
              <span className="result-card__truth">{statement.is_fake ? '가짜' : '진짜'}</span>
              <strong>{statement.vote_count}표</strong>
            </div>
            <p>{statement.content}</p>
            <div className="result-bar" aria-label={`${statement.vote_rate}% 득표`}>
              <span style={{ width: `${statement.vote_rate}%` }} />
            </div>
            {statement.voters ? (
              <div className="voter-list" aria-label={`${index + 1}번 문장에 투표한 사람`} role="group">
                <span className="voter-list__label">투표한 사람</span>
                {statement.voters.length ? (
                  <ul className="voter-list__badges">
                    {statement.voters.map((voter) => (
                      <li className="voter-badge" key={voter.id}>{voter.nickname}</li>
                    ))}
                  </ul>
                ) : <span className="voter-list__empty">없음</span>}
              </div>
            ) : null}
          </article>
        ))}
      </div>
      <div className="result-summary">
        <div><strong>{result.correct_voter_count}</strong><span>정답자</span></div>
        <div><strong>{result.fooled_participant_count}</strong><span>속은 사람</span></div>
      </div>
    </section>
  )
}

export function Leaderboard({ entries, title = '최종 순위' }: { entries: LeaderboardEntry[]; title?: string }) {
  return (
    <section className="leaderboard" aria-labelledby="leaderboard-title">
      <div className="section-heading section-heading--center">
        <span className="trophy"><CrownIcon /></span>
        <p className="eyebrow">게임 종료</p>
        <h2 id="leaderboard-title">{title}</h2>
      </div>
      <ol className="leaderboard__list">
        {entries.map((entry) => (
          <li className={entry.is_me ? 'is-me' : ''} key={entry.participant_id}>
            <span className={`rank rank--${entry.rank}`}>{entry.rank}</span>
            <span className="leaderboard__name">
              {entry.nickname}
              {entry.is_me ? <small>나</small> : null}
            </span>
            <strong>{entry.score}<small>점</small></strong>
          </li>
        ))}
      </ol>
    </section>
  )
}

export function PausedNotice({ snapshot }: { snapshot: TtfGameSnapshot }) {
  return (
    <section className="paused-card" role="status">
      <span className="pause-illustration" aria-hidden="true"><i /><i /></span>
      <p className="eyebrow">잠시 쉬어갈게요</p>
      <h2>게임이 일시 정지되었어요</h2>
      <p>진행자가 곧 게임을 이어갈 거예요. 이 화면에서 기다려 주세요.</p>
      {snapshot.current_round ? (
        <small>{snapshot.current_round.number} / {snapshot.current_round.total} 라운드 진행 중</small>
      ) : null}
    </section>
  )
}
