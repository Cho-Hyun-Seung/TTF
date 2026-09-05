import type { StatementDraft } from './types'

export const ROOM_CODE_PATTERN = /^[A-Z0-9]{6}$/

export function normalizeRoomCode(value: string) {
  return value.toUpperCase().replace(/[^A-Z0-9]/g, '').slice(0, 6)
}

export function characterCount(value: string) {
  return Array.from(value.trim()).length
}

export function validateNickname(value: string): string | null {
  const length = characterCount(value)
  if (length === 0) return '닉네임을 입력해 주세요.'
  if (length > 20) return '닉네임은 20자 이하로 입력해 주세요.'
  return null
}

export function validateStatements(
  statements: StatementDraft[],
  minLength: number,
  maxLength: number,
): string[] {
  const errors: string[] = []
  if (statements.length !== 3) return ['문장은 정확히 3개가 필요해요.']

  const fakeCount = statements.filter((statement) => statement.truth === 'FAKE').length
  if (fakeCount !== 1) errors.push('가짜 문장은 정확히 1개를 선택해 주세요.')

  statements.forEach((statement, index) => {
    const length = characterCount(statement.content)
    if (length < minLength || length > maxLength) {
      errors.push(`${index + 1}번 문장은 ${minLength}~${maxLength}자로 입력해 주세요.`)
    }
  })

  const normalized = statements.map((statement) =>
    statement.content.trim().replace(/\s+/g, ' ').toLocaleLowerCase('ko-KR'),
  )
  if (new Set(normalized).size !== normalized.length) {
    errors.push('서로 다른 문장 3개를 입력해 주세요.')
  }

  return errors
}

