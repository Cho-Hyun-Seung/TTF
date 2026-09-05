import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it } from 'vitest'
import { HomePage } from './HomePage'

describe('HomePage', () => {
  it('방 생성과 코드 입장 경로를 제공한다', () => {
    render(<MemoryRouter><HomePage /></MemoryRouter>)
    expect(screen.getByRole('link', { name: /게임방 만들기/ })).toHaveAttribute('href', '/rooms/new')
    expect(screen.getByRole('button', { name: /참여하기/ })).toBeVisible()
    expect(screen.getByLabelText('6자리 방 코드')).toHaveAttribute('maxLength', '6')
  })
})

