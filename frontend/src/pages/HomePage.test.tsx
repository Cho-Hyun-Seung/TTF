import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it } from 'vitest'
import { HomePage } from './HomePage'

afterEach(cleanup)

describe('HomePage', () => {
  it('방 생성과 코드 입장 경로를 제공한다', () => {
    render(<MemoryRouter><HomePage /></MemoryRouter>)
    expect(screen.getByRole('link', { name: /게임방 만들기/ })).toHaveAttribute('href', '/rooms/new')
    expect(screen.getByRole('button', { name: /참여하기/ })).toBeVisible()
    expect(screen.getByLabelText('6자리 방 코드')).toHaveAttribute('maxLength', '6')
  })

  it('게임 설명 모달을 열고 닫는다', () => {
    render(<MemoryRouter><HomePage /></MemoryRouter>)

    const openButton = screen.getByRole('button', { name: '게임 설명' })
    openButton.focus()
    fireEvent.click(openButton)

    expect(screen.getByRole('dialog', { name: '진짜 둘, 가짜 하나' })).toBeVisible()
    expect(screen.getByText('가짜를 맞힌 사람은 1점을 얻어요.')).toBeVisible()
    expect(screen.getByText('발표자는 속인 사람 한 명마다 1점을 얻어요.')).toBeVisible()
    expect(screen.getByRole('button', { name: '게임 설명 닫기' })).toHaveFocus()

    fireEvent.keyDown(document, { key: 'Escape' })

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(openButton).toHaveFocus()
  })
})
