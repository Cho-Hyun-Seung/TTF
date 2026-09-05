import type { RoomSnapshot, RoomStatus } from '../domain/types'
import type { ConnectionState } from '../hooks/useRoomSnapshot'
import { ConnectionBanner } from './Feedback'

const STATUS_LABELS: Record<RoomStatus, string> = {
  LOBBY: '입장 중',
  SUBMISSION: '문장 작성 중',
  READY: '시작 대기',
  ROUND_INTRO: '문장 공개',
  VOTING: '투표 중',
  VOTE_CLOSED: '투표 마감',
  RESULT: '결과 공개',
  PAUSED: '일시 정지',
  FINISHED: '게임 종료',
  CANCELLED: '게임 취소',
  EXPIRED: '방 만료',
}

export function RoomHeader({
  snapshot,
  connection,
  display = false,
}: {
  snapshot: RoomSnapshot
  connection: ConnectionState
  display?: boolean
}) {
  return (
    <>
      <ConnectionBanner state={connection} />
      <div className={`room-heading${display ? ' room-heading--display' : ''}`}>
        <div>
          <p className="eyebrow">방 코드 {snapshot.room.code}</p>
          <h1>{snapshot.room.name}</h1>
        </div>
        <span className={`status-badge status-badge--${snapshot.room.status.toLowerCase()}`}>
          <span aria-hidden="true" />
          {STATUS_LABELS[snapshot.room.status]}
        </span>
      </div>
    </>
  )
}

