import { act, cleanup, render, screen, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { TtfGameSnapshot } from '../domain/types'
import { useTtfGameSnapshot } from '../hooks/useTtfGameSnapshot'
import { DisplayPage } from './DisplayPage'
import { HostRoomPage } from './HostRoomPage'
import { ParticipantRoomPage } from './ParticipantRoomPage'

vi.mock('../hooks/useTtfGameSnapshot', () => ({ useTtfGameSnapshot: vi.fn() }))

function waitingSnapshot(): TtfGameSnapshot {
  return {
    version: 10,
    server_time: '2026-09-06T00:00:00Z',
    client_received_at_ms: Date.now(),
    room: {
      id: 'room_test', code: 'ABC123', name: '함께하는 게임', status: 'IN_GAME',
      joinable: false, participant_count: 3, settings: { max_participants: 30 },
      active_game: { id: 'game_test', type: 'TTF' },
    },
    game: {
      id: 'game_test', type: 'TTF', status: 'VOTE_CLOSED', ready_count: 3, round_count: 3,
      settings: {
        statement_min_length: 5, statement_max_length: 100, voting_duration_seconds: 60,
        speaker_order: 'JOIN_ORDER', anonymous_voting: true,
        round_count: 1,
        topics: [{ id: 'TRAVEL', title: '여행', example: '나는 혼자 해외여행을 떠난 적이 있다.' }],
      },
    },
    viewer: { role: 'PARTICIPANT', participant_id: 'player_2', nickname: '바다', is_ready: true },
    current_round: {
      id: 'round_test', number: 1, total: 3,
      topic: { id: 'TRAVEL', title: '여행', example: '나는 혼자 해외여행을 떠난 적이 있다.' },
      speaker: { id: 'player_1', nickname: '하늘' },
      statements: [
        { id: 'statement_1', content: '첫 번째 이야기입니다', display_order: 1 },
        { id: 'statement_2', content: '두 번째 이야기입니다', display_order: 2 },
        { id: 'statement_3', content: '세 번째 이야기입니다', display_order: 3 },
      ],
      result_reveals_at: '2026-09-06T00:00:02Z',
      vote_progress: { completed: 2, eligible: 2 },
    },
  }
}

function mockSnapshot(snapshot: TtfGameSnapshot) {
  vi.mocked(useTtfGameSnapshot).mockReturnValue({
    snapshot, error: null, loading: false, connection: 'connected', refresh: vi.fn(),
  })
}

function revealedSnapshot(showVoters: boolean): TtfGameSnapshot {
  const snapshot = waitingSnapshot()
  const round = snapshot.current_round!
  return {
    ...snapshot,
    version: 11,
    game: {
      ...snapshot.game,
      status: 'RESULT',
      settings: { ...snapshot.game.settings, anonymous_voting: !showVoters },
    },
    current_round: {
      ...round,
      result_reveals_at: undefined,
      result: {
        fake_statement_id: 'statement_2',
        correct_voter_count: 1,
        fooled_participant_count: 1,
        statements: round.statements.map((statement, index) => ({
          ...statement,
          is_fake: statement.id === 'statement_2',
          vote_count: index === 0 ? 2 : 0,
          vote_rate: index === 0 ? 100 : 0,
          voters: showVoters && index === 0
            ? [{ id: 'player_2', nickname: '바다' }, { id: 'player_3', nickname: '새봄' }]
            : showVoters ? [] : undefined,
        })),
        score_changes: [{ participant_id: 'player_2', nickname: '바다', delta: 1, total: 1 }],
      },
    },
  }
}

afterEach(() => {
  cleanup()
  vi.useRealTimers()
})

describe('전원 투표 후 결과 공개', () => {
  it.each([
    ['참가자', ParticipantRoomPage], ['진행자', HostRoomPage], ['공용 화면', DisplayPage],
  ] as const)('%s는 안내를 보여주고 서버 결과가 도착하면 전환한다', (_role, Page) => {
    vi.useFakeTimers()
    const snapshot = waitingSnapshot()
    mockSnapshot(snapshot)
    const view = <MemoryRouter initialEntries={['/game_test']}><Routes><Route path="/:gameId" element={<Page />} /></Routes></MemoryRouter>
    const { rerender } = render(view)
    expect(screen.getByRole('status')).toHaveTextContent('모두 투표했어요!')
    expect(screen.getByRole('heading', { name: '잠시 후 결과가 공개돼요' })).toBeVisible()
    expect(screen.queryByRole('button', { name: '정답 공개' })).not.toBeInTheDocument()

    act(() => vi.advanceTimersByTime(3_000))
    expect(screen.getByRole('heading', { name: '잠시 후 결과가 공개돼요' })).toBeVisible()

    const round = snapshot.current_round!
    mockSnapshot({
      ...snapshot,
      version: 11,
      game: { ...snapshot.game, status: 'RESULT' },
      current_round: {
        ...round,
        result_reveals_at: undefined,
        result: {
          fake_statement_id: 'statement_2', correct_voter_count: 2, fooled_participant_count: 0,
          statements: round.statements.map((statement) => ({
            ...statement, is_fake: statement.id === 'statement_2',
            vote_count: statement.id === 'statement_2' ? 2 : 0,
            vote_rate: statement.id === 'statement_2' ? 100 : 0,
          })),
          score_changes: [{ participant_id: 'player_2', nickname: '바다', delta: 1, total: 1 }],
        },
      },
    })
    rerender(<MemoryRouter initialEntries={['/game_test']}><Routes><Route path="/:gameId" element={<Page />} /></Routes></MemoryRouter>)
    expect(screen.queryByText('잠시 후 결과가 공개돼요')).not.toBeInTheDocument()
    expect(screen.getByText('가짜')).toBeVisible()
  })

  it('수동 마감 후에는 진행자의 결과 공개 버튼을 유지한다', () => {
    const snapshot = waitingSnapshot()
    snapshot.current_round!.result_reveals_at = undefined
    mockSnapshot(snapshot)
    render(<MemoryRouter initialEntries={['/game_test']}><Routes><Route path="/:gameId" element={<HostRoomPage />} /></Routes></MemoryRouter>)
    expect(screen.getByRole('button', { name: '정답 공개' })).toBeVisible()
    expect(screen.queryByText('모두 투표했어요!')).not.toBeInTheDocument()
  })
})

describe('결과의 투표자 공개', () => {
  it.each([
    ['참가자', ParticipantRoomPage], ['진행자', HostRoomPage], ['공용 화면', DisplayPage],
  ] as const)('%s 결과에서 투표자를 문장 아래 배지로 보여준다', (_role, Page) => {
    mockSnapshot(revealedSnapshot(true))
    render(<MemoryRouter initialEntries={['/game_test']}><Routes><Route path="/:gameId" element={<Page />} /></Routes></MemoryRouter>)

    const firstStatementVoters = screen.getByRole('group', { name: '1번 문장에 투표한 사람' })
    expect(within(firstStatementVoters).getByText('바다')).toHaveClass('voter-badge')
    expect(within(firstStatementVoters).getByText('새봄')).toHaveClass('voter-badge')
    expect(screen.getByRole('group', { name: '2번 문장에 투표한 사람' })).toHaveTextContent('없음')
  })

  it('투표자 공개를 끄면 결과에 투표자 영역을 표시하지 않는다', () => {
    mockSnapshot(revealedSnapshot(false))
    render(<MemoryRouter initialEntries={['/game_test']}><Routes><Route path="/:gameId" element={<ParticipantRoomPage />} /></Routes></MemoryRouter>)

    expect(screen.queryByText('투표한 사람')).not.toBeInTheDocument()
    expect(screen.queryByRole('group', { name: /문장에 투표한 사람/ })).not.toBeInTheDocument()
  })
})
