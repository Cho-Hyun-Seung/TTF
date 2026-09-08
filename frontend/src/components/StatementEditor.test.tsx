import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { StatementEditor } from './StatementEditor'

const topics = [
  { id: 'TRAVEL', title: '여행', example: '나는 혼자 해외여행을 떠난 적이 있다.' },
  { id: 'FOOD', title: '음식', example: '나는 한 번도 커피를 마셔본 적이 없다.' },
]

afterEach(cleanup)

describe('StatementEditor', () => {
  it('선택된 라운드 주제와 문장 예시를 탭으로 보여준다', () => {
    render(
      <StatementEditor
        gameId="game-1"
        maxLength={100}
        minLength={5}
        onSaved={vi.fn()}
        topics={topics}
      />,
    )

    expect(screen.getByRole('tab', { name: /여행/ })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByText('나는 혼자 해외여행을 떠난 적이 있다.')).toBeVisible()
    expect(screen.getAllByRole('textbox')).toHaveLength(3)

    fireEvent.click(screen.getByRole('tab', { name: /음식/ }))

    expect(screen.getByRole('tab', { name: /음식/ })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByText('나는 한 번도 커피를 마셔본 적이 없다.')).toBeVisible()
    expect(screen.getByLabelText('음식 문장 1')).toHaveAttribute('placeholder', '나는 한 번도 커피를 마셔본 적이 없다.')
  })
})
