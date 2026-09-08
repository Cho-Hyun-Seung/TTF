import { useState, type ReactNode } from 'react'
import { Link, useParams } from 'react-router-dom'
import { AppShell } from '../components/AppShell'
import { ErrorView, InlineError, LoadingView } from '../components/Feedback'
import {
  Countdown,
  Leaderboard,
  PausedNotice,
  ResultPanel,
  ResultRevealNotice,
  RoundHeading,
  StatementCards,
  VoteProgress,
} from '../components/GameViews'
import { PauseIcon, PlayIcon, UsersIcon } from '../components/Icons'
import { QrCode } from '../components/QrCode'
import { RoomHeader } from '../components/RoomHeader'
import type { ParticipantSummary, TtfGameSnapshot } from '../domain/types'
import { useTtfGameSnapshot } from '../hooks/useTtfGameSnapshot'
import { ApiError, roomApi, ttfGameApi } from '../lib/api'

function ParticipantList({
  participants,
  onKick,
  busy,
}: {
  participants: ParticipantSummary[]
  onKick: (participant: ParticipantSummary) => void
  busy: boolean
}) {
  return (
    <section className="participant-panel">
      <div className="section-heading section-heading--row">
        <div><p className="eyebrow">참가자</p><h2>{participants.length}명이 들어왔어요</h2></div>
        <UsersIcon />
      </div>
      <ul className="participant-list">
        {participants.map((participant) => (
          <li key={participant.id}>
            <span className={`presence-dot${participant.connection_status === 'OFFLINE' ? ' is-offline' : ''}`} />
            <span className="participant-name">{participant.nickname}</span>
            <span className={participant.is_ready ? 'ready-label is-ready' : 'ready-label'}>
              {participant.is_ready ? '준비 완료' : '작성 중'}
            </span>
            <button aria-label={`${participant.nickname} 내보내기`} disabled={busy} onClick={() => onKick(participant)} type="button">내보내기</button>
          </li>
        ))}
      </ul>
      {!participants.length ? <p className="empty-list">QR을 스캔한 참가자가 여기에 나타나요.</p> : null}
    </section>
  )
}

function HostLobby({
  snapshot,
  busy,
  onStart,
  onKick,
}: {
  snapshot: TtfGameSnapshot
  busy: boolean
  onStart: () => void
  onKick: (participant: ParticipantSummary) => void
}) {
  const joinUrl = `${window.location.origin}/join/${snapshot.room.code}`
  const everyoneReady = snapshot.room.participant_count >= 2 && snapshot.game.ready_count === snapshot.room.participant_count
  return (
    <>
      <div className="host-lobby-grid">
        <QrCode code={snapshot.room.code} value={joinUrl} />
        <ParticipantList busy={busy} onKick={onKick} participants={snapshot.participants ?? []} />
      </div>
      <div className="host-start-bar">
        <div>
          <strong>{snapshot.game.ready_count} / {snapshot.room.participant_count}명 준비 완료</strong>
          <p>{snapshot.room.participant_count < 2 ? '게임을 시작하려면 참가자가 2명 이상 필요해요.' : everyoneReady ? '모두 준비됐어요. 게임을 시작해 보세요!' : '아직 문장을 작성 중인 참가자가 있어요.'}</p>
          <p className="host-topic-summary">{snapshot.game.settings.round_count}개 주제 · {snapshot.game.settings.topics.map((topic) => topic.title).join(' · ')}</p>
        </div>
        <button className="button button--primary" disabled={busy || !everyoneReady} onClick={onStart} type="button">
          <PlayIcon /> 게임 시작
        </button>
      </div>
    </>
  )
}

function HostRound({ snapshot, action, busy }: { snapshot: TtfGameSnapshot; action: (work: () => Promise<void>) => void; busy: boolean }) {
  const round = snapshot.current_round
  if (!round) return null
  const isLastRound = round.number === round.total

  return (
    <>
      <RoundHeading round={round} />
      <div className="host-round-grid">
        <section className="round-main card">
          {snapshot.game.status === 'RESULT' && round.result ? <ResultPanel result={round.result} /> : <StatementCards statements={round.statements} />}
        </section>
        <aside className="control-panel">
          {snapshot.game.status === 'ROUND_INTRO' ? (
            <>
              <p className="eyebrow">발표 준비</p>
              <h2>{round.speaker.nickname}님의 설명을 들어보세요</h2>
              <p>설명이 끝난 뒤 투표를 시작하세요.</p>
              <button className="button button--primary button--block" disabled={busy} onClick={() => action(() => ttfGameApi.startVoting(snapshot.game.id, round.id))} type="button"><PlayIcon /> 투표 시작</button>
              <button className="button button--secondary button--block" disabled={busy} onClick={() => action(() => ttfGameApi.skipRound(snapshot.game.id, round.id))} type="button">이 라운드 건너뛰기</button>
            </>
          ) : null}
          {snapshot.game.status === 'VOTING' ? (
            <>
              <Countdown clientReceivedAt={snapshot.client_received_at_ms} endsAt={round.voting_ends_at} large serverTime={snapshot.server_time} />
              <VoteProgress {...round.vote_progress} />
              <button className="button button--secondary button--block" disabled={busy} onClick={() => action(() => ttfGameApi.extendVoting(snapshot.game.id, round.id))} type="button">15초 연장</button>
              <button className="button button--danger-ghost button--block" disabled={busy} onClick={() => action(() => ttfGameApi.closeVoting(snapshot.game.id, round.id))} type="button">투표 조기 마감</button>
            </>
          ) : null}
          {snapshot.game.status === 'VOTE_CLOSED' ? (
            round.result_reveals_at ? <ResultRevealNotice /> :
            <>
              <span className="closed-symbol" aria-hidden="true">✓</span>
              <p className="eyebrow">투표 완료</p>
              <h2>이제 정답을 공개할까요?</h2>
              <VoteProgress {...round.vote_progress} />
              <button className="button button--primary button--block" disabled={busy} onClick={() => action(() => ttfGameApi.revealResult(snapshot.game.id, round.id))} type="button">정답 공개</button>
            </>
          ) : null}
          {snapshot.game.status === 'RESULT' ? (
            <>
              <p className="eyebrow">라운드 완료</p>
              <h2>{isLastRound ? '모든 이야기를 들었어요!' : '다음 사람을 만나볼까요?'}</h2>
              <button className="button button--primary button--block" disabled={busy} onClick={() => action(() => ttfGameApi.nextRound(snapshot.game.id))} type="button">
                {isLastRound ? '최종 순위 보기' : '다음 라운드'}
              </button>
            </>
          ) : null}
        </aside>
      </div>
    </>
  )
}

export function HostRoomPage() {
  const { gameId } = useParams()
  const { snapshot, error, loading, connection, refresh } = useTtfGameSnapshot(gameId, 'host')
  const [busy, setBusy] = useState(false)
  const [actionError, setActionError] = useState('')

  const action = async (work: () => Promise<void>) => {
    setBusy(true)
    setActionError('')
    try {
      await work()
      await refresh()
    } catch (caught) {
      setActionError(caught instanceof ApiError ? caught.message : '명령을 처리하지 못했어요. 현재 상태를 확인해 주세요.')
      await refresh()
    } finally {
      setBusy(false)
    }
  }

  const kick = (participant: ParticipantSummary) => {
    if (!window.confirm(`${participant.nickname}님을 게임방에서 내보낼까요?`)) return
    void action(() => roomApi.removeParticipant(snapshot?.room.id ?? '', participant.id))
  }

  let content: ReactNode = null
  if (snapshot) {
    if (['LOBBY', 'SUBMISSION', 'READY'].includes(snapshot.game.status)) {
      content = <HostLobby busy={busy} onKick={kick} onStart={() => void action(() => ttfGameApi.start(snapshot.game.id))} snapshot={snapshot} />
    } else if (snapshot.game.status === 'PAUSED') {
      content = <PausedNotice snapshot={snapshot} />
    } else if (['ROUND_INTRO', 'VOTING', 'VOTE_CLOSED', 'RESULT'].includes(snapshot.game.status)) {
      content = <HostRound action={(work) => void action(work)} busy={busy} snapshot={snapshot} />
    } else if (snapshot.game.status === 'FINISHED') {
      content = <Leaderboard entries={snapshot.leaderboard ?? []} />
    } else {
      content = <ErrorView message="이 게임은 더 이상 진행할 수 없어요." title="게임이 종료되었어요" action={<Link className="button button--secondary" to="/">홈으로 돌아가기</Link>} />
    }
  }

  if (loading) return <AppShell><LoadingView /></AppShell>
  if (error || !snapshot || !gameId) {
    return <AppShell><ErrorView message={error?.message ?? '진행자 화면을 불러오지 못했어요.'} onRetry={() => void refresh()} /></AppShell>
  }

  const activeGame = ['ROUND_INTRO', 'VOTING', 'VOTE_CLOSED', 'RESULT', 'PAUSED'].includes(snapshot.game.status)
  const canEnd = !['FINISHED', 'CANCELLED'].includes(snapshot.game.status)

  const endGame = () => {
    const beforeStart = ['LOBBY', 'SUBMISSION', 'READY'].includes(snapshot.game.status)
    const message = beforeStart
      ? '게임방을 취소할까요? 참가자는 더 이상 이 방에 들어올 수 없어요.'
      : '게임을 지금 종료할까요? 현재까지의 점수로 최종 순위가 표시돼요.'
    if (!window.confirm(message)) return
    void action(() => beforeStart ? roomApi.cancel(snapshot.room.id) : ttfGameApi.finish(snapshot.game.id))
  }

  return (
    <AppShell
      headerAside={
        <div className="host-top-actions">
          <a className="text-link" href={`/display/${snapshot.game.id}`} rel="noreferrer" target="_blank">공용 화면 열기</a>
          {activeGame ? (
            snapshot.game.status === 'PAUSED' ? (
              <button className="icon-button" disabled={busy} onClick={() => void action(() => ttfGameApi.resume(snapshot.game.id))} type="button"><PlayIcon /> 재개</button>
            ) : (
              <button className="icon-button" disabled={busy} onClick={() => void action(() => ttfGameApi.pause(snapshot.game.id))} type="button"><PauseIcon /> 일시 정지</button>
            )
          ) : null}
          {canEnd ? <button className="top-danger-action" disabled={busy} onClick={endGame} type="button">게임 종료</button> : null}
        </div>
      }
    >
      <RoomHeader connection={connection} snapshot={snapshot} />
      {actionError ? <InlineError>{actionError}</InlineError> : null}
      <div className="game-content game-content--host">{content}</div>
    </AppShell>
  )
}
