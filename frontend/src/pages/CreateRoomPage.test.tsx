import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { CreateRoomPage } from './CreateRoomPage'

const fetchMock = vi.fn()

function success(data: unknown, status = 200) {
  return {
    ok: true,
    status,
    json: vi.fn().mockResolvedValue({
      success: true,
      data,
      response_time: '2026-09-08T00:00:00Z',
    }),
  }
}

const topics = [
  { id: 'TRAVEL', title: '여행', example: '나는 혼자 해외여행을 떠난 적이 있다.' },
  { id: 'FOOD', title: '음식', example: '나는 한 번도 커피를 마셔본 적이 없다.' },
  { id: 'TALENT', title: '특기', example: '나는 세 가지 악기를 연주할 수 있다.' },
  { id: 'CHILDHOOD', title: '어린 시절', example: '나는 어릴 때 전국 대회에서 상을 받은 적이 있다.' },
]

beforeEach(() => {
  vi.stubGlobal('fetch', fetchMock)
  fetchMock.mockReset()
})

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe('CreateRoomPage', () => {
  it('라운드 주제와 투표자 공개 옵션을 서버 설정으로 전달한다', async () => {
    fetchMock
      .mockResolvedValueOnce(success(topics))
      .mockResolvedValueOnce(success({
        room: { id: 'room-1', code: 'ABC123', join_url: '/join/ABC123' },
        game: { id: 'game-1', type: 'TTF' },
      }, 201))

    render(
      <MemoryRouter initialEntries={['/rooms/new']}>
        <Routes>
          <Route element={<CreateRoomPage />} path="/rooms/new" />
          <Route element={<p>방 생성 완료</p>} path="/host/:gameId" />
        </Routes>
      </MemoryRouter>,
    )

    expect(await screen.findByRole('checkbox', { name: /여행/ })).toBeChecked()
    expect(screen.getByRole('checkbox', { name: /음식/ })).toBeChecked()
    expect(screen.getByRole('checkbox', { name: /특기/ })).toBeChecked()
    const showVoters = screen.getByRole('switch', { name: /투표자 공개/ })
    expect(showVoters).not.toBeChecked()

    fireEvent.change(screen.getByLabelText('주제 라운드 수'), { target: { value: '2' } })
    fireEvent.click(showVoters)
    expect(screen.getAllByRole('checkbox').filter((checkbox) => (checkbox as HTMLInputElement).checked)).toHaveLength(2)

    fireEvent.change(screen.getByLabelText('게임방 이름'), { target: { value: '워크숍' } })
    fireEvent.click(screen.getByRole('button', { name: /게임방 만들기/ }))

    await screen.findByText('방 생성 완료')
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2))
    const requestBody = JSON.parse(String(fetchMock.mock.calls[1][1]?.body))
    expect(requestBody.game.settings).toMatchObject({
      anonymous_voting: false,
      round_count: 2,
      topic_ids: ['TRAVEL', 'FOOD'],
    })
  })
})
