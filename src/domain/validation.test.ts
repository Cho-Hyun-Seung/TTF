import { describe, expect, it } from 'vitest'
import { characterCount, normalizeRoomCode, validateNickname, validateStatements } from './validation'

describe('normalizeRoomCode', () => {
  it('대문자 영문과 숫자 여섯 자리만 남긴다', () => {
    expect(normalizeRoomCode('ab-12 cd!')).toBe('AB12CD')
  })
})

describe('characterCount', () => {
  it('이모지를 사용자에게 보이는 코드 포인트 단위로 센다', () => {
    expect(characterCount('  안녕🙂  ')).toBe(3)
  })
})

describe('validateNickname', () => {
  it('공백뿐인 닉네임을 거절한다', () => {
    expect(validateNickname('   ')).toBe('닉네임을 입력해 주세요.')
  })

  it('20자를 넘는 닉네임을 거절한다', () => {
    expect(validateNickname('가'.repeat(21))).toContain('20자 이하')
  })
})

describe('validateStatements', () => {
  it('진짜 둘과 가짜 하나인 서로 다른 문장을 허용한다', () => {
    expect(validateStatements([
      { content: '나는 매일 아침 일찍 일어난다', truth: 'TRUE' },
      { content: '나는 제주도에서 한 달을 살았다', truth: 'TRUE' },
      { content: '나는 커피를 한 번도 마신 적 없다', truth: 'FAKE' },
    ], 5, 100)).toEqual([])
  })

  it('가짜 개수와 공백이 다른 중복 문장을 함께 검증한다', () => {
    const errors = validateStatements([
      { content: '같은 문장입니다', truth: 'TRUE' },
      { content: '같은   문장입니다 ', truth: 'TRUE' },
      { content: '다른 문장입니다', truth: 'TRUE' },
    ], 5, 100)
    expect(errors).toContain('가짜 문장은 정확히 1개를 선택해 주세요.')
    expect(errors).toContain('서로 다른 문장 3개를 입력해 주세요.')
  })
})

