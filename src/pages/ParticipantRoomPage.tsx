import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { AppShell } from '../components/AppShell'
import { ErrorView, InlineError, LoadingView } from '../components/Feedback'
import {
  Countdown,
  Leaderboard,
  PausedNotice,
  ResultPanel,
  RoundHeading,
  StatementCards,
  VoteProgress,
} from '../components/GameViews'
import { CheckIcon } from '../components/Icons'
import { RoomHeader } from '../components/RoomHeader'
import { StatementEditor } from '../components/StatementEditor'
import type { RoomSnapshot, RoundSnapshot } from '../domain/types'
import { useRoomSnapshot } from '../hooks/useRoomSnapshot'
import { ApiError, api } from '../lib/api'

function WaitingForStart({ snapshot, onEdit }: { snapshot: RoomSnapshot; onEdit: () => void }) {
  return (
    <section className="waiting-card">
      <span className="ready-check"><CheckIcon /></span>
      <p className="eyebrow">준비 완료</p>
      <h2>문장이 안전하게 저장되었어요</h2>
      <p>모두 준비되면 진행자가 게임을 시작할 거예요.</p>
      <div className="waiting-card__count">
        <strong>{snapshot.room.ready_count}</strong>
        <span>/ {snapshot.room.participant_count}명 준비</span>
      </div>
      <div className="waiting-dots" aria-hidden="true"><span /><span /><span /></div>
      <button className="text-button" onClick={onEdit} type="button">제출한 문장 수정하기</button>
    </section>
  )
}

function ParticipantRound({
  roomId,
  snapshot,
  refresh,
}: {
  roomId: string
  snapshot: RoomSnapshot
  refresh: () => Promise<void>
}) {
  const round = snapshot.current_round as RoundSnapshot
  const isSpeaker = snapshot.viewer.participant_id === round.speaker.id
  const [pendingId, setPendingId] = useState<string | null>(null)
  const [error, setError] = useState('')

  const vote = async (statementId: string) => {
    setPendingId(statementId)
    setError('')
    try {
      await api.submitVote(roomId, round.id, statementId)
      await refresh()
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : '투표를 저장하지 못했어요. 다시 시도해 주세요.')
    } finally {
      setPendingId(null)
    }
  }

  if (snapshot.room.status === 'RESULT' && round.result) {
    const myScore = round.result.score_changes.find((change) => change.participant_id === snapshot.viewer.participant_id)
    return (
      <>
        <RoundHeading round={round} />
        <ResultPanel result={round.result} />
        <section className="my-score-card">
          <span>이번 라운드</span>
          <strong>+{myScore?.delta ?? 0}점</strong>
          <small>누적 {myScore?.total ?? 0}점</small>
        </section>
        <p className="next-round-note">진행자가 다음 라운드를 준비하고 있어요.</p>
      </>
    )
  }

  return (
    <>
      <RoundHeading round={round} />
      <div className="round-layout">
        <section className="round-main">
          {snapshot.room.status === 'ROUND_INTRO' ? (
            <>
              <div className="section-heading"><p className="eyebrow">세 문장</p><h2>가짜 같은 문장을 눈여겨보세요</h2></div>
              <StatementCards statements={round.statements} />
              <p className="next-round-note">진행자의 설명이 끝나면 투표가 시작돼요.</p>
            </>
          ) : null}

          {snapshot.room.status === 'VOTING' && isSpeaker ? (
            <section className="speaker-wait">
              <Countdown clientReceivedAt={snapshot.client_received_at_ms} endsAt={round.voting_ends_at} serverTime={snapshot.server_time} />
              <span className="speaker-badge">내 이야기</span>
              <h2>모두가 추리하고 있어요</h2>
              <p>발표자는 자신의 라운드에 투표할 수 없어요.</p>
              <VoteProgress {...round.vote_progress} />
            </section>
          ) : null}

          {snapshot.room.status === 'VOTING' && !isSpeaker ? (
            <>
              <div className="vote-heading">
                <div><p className="eyebrow">당신의 선택은?</p><h2>가짜 문장 하나를 골라주세요</h2></div>
                <Countdown clientReceivedAt={snapshot.client_received_at_ms} endsAt={round.voting_ends_at} serverTime={snapshot.server_time} />
              </div>
              <StatementCards
                disabled={pendingId !== null}
                onSelect={(statementId) => void vote(statementId)}
                selectedId={pendingId ?? round.my_vote_statement_id}
                statements={round.statements}
              />
              {pendingId ? <p className="saving-note" role="status">선택을 저장하고 있어요…</p> : null}
              {round.my_vote_statement_id && !pendingId ? <p className="saved-note" role="status"><CheckIcon /> 선택이 저장됐어요. 마감 전까지 바꿀 수 있어요.</p> : null}
              {error ? <InlineError>{error}</InlineError> : null}
            </>
          ) : null}

          {snapshot.room.status === 'VOTE_CLOSED' ? (
            <section className="speaker-wait">
              <span className="closed-symbol" aria-hidden="true">✓</span>
              <p className="eyebrow">투표 마감</p>
              <h2>과연 가짜는 무엇이었을까요?</h2>
              <p>진행자가 곧 정답을 공개할 거예요.</p>
            </section>
          ) : null}
        </section>
      </div>
    </>
  )
}

export function ParticipantRoomPage() {
  const { roomId } = useParams()
  const { snapshot, error, loading, connection, refresh } = useRoomSnapshot(roomId, 'participant')
  const [editing, setEditing] = useState(false)

  if (loading) return <AppShell compact><LoadingView /></AppShell>
  if (error || !snapshot || !roomId) {
    const expired = error instanceof ApiError && ['ROOM_EXPIRED', 'ROOM_NOT_FOUND'].includes(error.code)
    return (
      <AppShell compact>
        <ErrorView
          title={expired ? '게임방이 만료되었어요' : undefined}
          message={error?.message ?? '게임 상태를 불러오지 못했어요.'}
          onRetry={expired ? undefined : () => void refresh()}
          action={expired ? <Link className="button button--secondary" to="/">홈으로 돌아가기</Link> : undefined}
        />
      </AppShell>
    )
  }

  if (snapshot.room.status === 'CANCELLED' || snapshot.room.status === 'EXPIRED') {
    return <AppShell compact><ErrorView title="게임이 종료되었어요" message="진행자가 게임을 취소했거나 게임방이 만료되었어요." action={<Link className="button button--secondary" to="/">홈으로 돌아가기</Link>} /></AppShell>
  }

  return (
    <AppShell compact headerAside={<span className="viewer-name">{snapshot.viewer.nickname}</span>}>
      <RoomHeader connection={connection} snapshot={snapshot} />
      <div className="game-content">
        {snapshot.room.status === 'PAUSED' ? <PausedNotice snapshot={snapshot} /> : null}
        {['LOBBY', 'SUBMISSION', 'READY'].includes(snapshot.room.status) && (!snapshot.viewer.is_ready || editing) ? (
          <StatementEditor
            initialStatements={editing ? snapshot.my_statements?.map((statement) => ({
              content: statement.content,
              truth: statement.is_fake ? 'FAKE' : 'TRUE',
            })) : undefined}
            maxLength={snapshot.room.settings.statement_max_length}
            minLength={snapshot.room.settings.statement_min_length}
            onSaved={async () => {
              setEditing(false)
              await refresh()
            }}
            roomId={roomId}
          />
        ) : null}
        {['LOBBY', 'SUBMISSION', 'READY'].includes(snapshot.room.status) && snapshot.viewer.is_ready && !editing ? <WaitingForStart onEdit={() => setEditing(true)} snapshot={snapshot} /> : null}
        {['ROUND_INTRO', 'VOTING', 'VOTE_CLOSED', 'RESULT'].includes(snapshot.room.status) && snapshot.current_round ? (
          <ParticipantRound key={snapshot.current_round.id} refresh={refresh} roomId={roomId} snapshot={snapshot} />
        ) : null}
        {snapshot.room.status === 'FINISHED' ? <Leaderboard entries={snapshot.leaderboard ?? []} /> : null}
      </div>
    </AppShell>
  )
}
