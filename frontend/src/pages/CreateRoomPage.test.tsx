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
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('CreateRoomPage', () => {
  it('랜덤 선택 결과를 숨기고 생성 시 현재 라운드 수만큼 중복 없이 뽑는다', async () => {
    fetchMock
      .mockResolvedValueOnce(success(topics))
      .mockResolvedValueOnce(success({
        room: { id: 'room-1', code: 'ABC123', join_url: '/join/ABC123' },
        game: { id: 'game-1', type: 'TTF' },
      }, 201))
    render(<MemoryRouter><CreateRoomPage /></MemoryRouter>)
    await screen.findByRole('checkbox', { name: /여행/ })
    const random = vi.spyOn(Math, 'random').mockReturnValue(0)
    fireEvent.click(screen.getByRole('button', { name: '주제 랜덤 선택' }))
    expect(screen.queryAllByRole('checkbox')).toHaveLength(0)
    for (const topic of topics) {
      expect(screen.queryByText(topic.title)).not.toBeInTheDocument()
      expect(screen.queryByText(topic.example)).not.toBeInTheDocument()
    }
    expect(random).not.toHaveBeenCalled()
    fireEvent.change(screen.getByLabelText('주제 라운드 수'), { target: { value: '2' } })
    expect(screen.queryAllByRole('checkbox')).toHaveLength(0)
    expect(fetchMock).toHaveBeenCalledTimes(1)
    fireEvent.change(screen.getByLabelText('게임방 이름'), { target: { value: '랜덤 주제' } })
    fireEvent.click(screen.getByRole('button', { name: /게임방 만들기/ }))
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2))
    const requestBody = JSON.parse(String(fetchMock.mock.calls[1][1]?.body))
    expect(requestBody.game.settings).toMatchObject({ round_count: 2, topic_ids: ['FOOD', 'TALENT'] })
  })

  it('직접 선택으로 돌아가면 이전 수동 선택을 복원한다', async () => {
    fetchMock.mockResolvedValueOnce(success(topics))
    render(<MemoryRouter><CreateRoomPage /></MemoryRouter>)
    await screen.findByRole('checkbox', { name: /여행/ })
    fireEvent.click(screen.getByRole('checkbox', { name: /여행/ }))
    fireEvent.click(screen.getByRole('checkbox', { name: /어린 시절/ }))
    fireEvent.click(screen.getByRole('button', { name: '주제 랜덤 선택' }))
    fireEvent.click(screen.getByRole('button', { name: '주제 직접 선택' }))
    expect(screen.getByRole('checkbox', { name: /여행/ })).not.toBeChecked()
    expect(screen.getByRole('checkbox', { name: /음식/ })).toBeChecked()
    expect(screen.getByRole('checkbox', { name: /특기/ })).toBeChecked()
    expect(screen.getByRole('checkbox', { name: /어린 시절/ })).toBeChecked()
  })

  it('로딩과 오류 중에는 랜덤 선택을 막고 목록 재조회 후 허용한다', async () => {
    fetchMock.mockRejectedValueOnce(new Error('offline')).mockResolvedValueOnce(success(topics))
    render(<MemoryRouter><CreateRoomPage /></MemoryRouter>)
    const randomButton = screen.getByRole('button', { name: '주제 랜덤 선택' })
    expect(randomButton).toBeDisabled()
    await screen.findByText('주제 목록을 불러오지 못했어요. 다시 시도해 주세요.')
    expect(randomButton).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: '다시 불러오기' }))
    await waitFor(() => expect(randomButton).toBeEnabled())
  })

  it('유효한 라운드 수에서만 랜덤 선택을 허용한다', async () => {
    fetchMock.mockResolvedValueOnce(success(topics))
    render(<MemoryRouter><CreateRoomPage /></MemoryRouter>)
    await screen.findByRole('checkbox', { name: /여행/ })
    const count = screen.getByLabelText('주제 라운드 수')
    const randomButton = screen.getByRole('button', { name: '주제 랜덤 선택' })
    for (const value of ['0', '1.5', '5']) {
      fireEvent.change(count, { target: { value } })
      expect(randomButton).toBeDisabled()
    }
    for (const value of ['1', '4']) {
      fireEvent.change(count, { target: { value } })
      expect(randomButton).toBeEnabled()
      fireEvent.click(randomButton)
      expect(screen.queryAllByRole('checkbox')).toHaveLength(0)
      fireEvent.click(screen.getByRole('button', { name: '주제 직접 선택' }))
    }
  })

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
