import { useMemo, useState, type FormEvent } from 'react'
import type { StatementDraft } from '../domain/types'
import { characterCount, validateStatements } from '../domain/validation'
import { ApiError, api } from '../lib/api'
import { InlineError } from './Feedback'

const INITIAL_STATEMENTS: StatementDraft[] = [
  { content: '', truth: 'TRUE' },
  { content: '', truth: 'TRUE' },
  { content: '', truth: 'FAKE' },
]

export function StatementEditor({
  roomId,
  minLength,
  maxLength,
  initialStatements,
  onSaved,
}: {
  roomId: string
  minLength: number
  maxLength: number
  initialStatements?: StatementDraft[]
  onSaved: () => Promise<void> | void
}) {
  const [statements, setStatements] = useState(() => initialStatements ?? INITIAL_STATEMENTS)
  const [submitted, setSubmitted] = useState(false)
  const [busy, setBusy] = useState(false)
  const [serverError, setServerError] = useState('')
  const errors = useMemo(
    () => (submitted ? validateStatements(statements, minLength, maxLength) : []),
    [maxLength, minLength, statements, submitted],
  )

  const updateStatement = (index: number, patch: Partial<StatementDraft>) => {
    setStatements((current) => current.map((statement, itemIndex) =>
      itemIndex === index ? { ...statement, ...patch } : statement,
    ))
  }

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    setSubmitted(true)
    setServerError('')
    const nextErrors = validateStatements(statements, minLength, maxLength)
    if (nextErrors.length) return

    setBusy(true)
    try {
      await api.saveStatements(roomId, statements)
      await onSaved()
    } catch (caught) {
      setServerError(caught instanceof ApiError ? caught.message : '문장을 저장하지 못했어요. 다시 시도해 주세요.')
    } finally {
      setBusy(false)
    }
  }

  return (
    <form className="statement-form" onSubmit={(event) => void submit(event)} noValidate>
      <div className="section-heading">
        <p className="eyebrow">나만의 이야기</p>
        <h2>진짜 둘, 가짜 하나를 적어주세요</h2>
        <p>정답은 공개 전까지 누구에게도 보이지 않아요.</p>
      </div>

      <div className="privacy-note">
        이름, 연락처처럼 민감한 개인정보는 문장에 적지 마세요.
      </div>

      <div className="statement-fields">
        {statements.map((statement, index) => {
          const count = characterCount(statement.content)
          return (
            <fieldset className="statement-field" key={index}>
              <legend>문장 {index + 1}</legend>
              <div className="truth-toggle" aria-label={`문장 ${index + 1} 진위`}>
                <button
                  aria-pressed={statement.truth === 'TRUE'}
                  className={statement.truth === 'TRUE' ? 'is-active' : ''}
                  onClick={() => updateStatement(index, { truth: 'TRUE' })}
                  type="button"
                >
                  진짜
                </button>
                <button
                  aria-pressed={statement.truth === 'FAKE'}
                  className={statement.truth === 'FAKE' ? 'is-active is-fake' : ''}
                  onClick={() => updateStatement(index, { truth: 'FAKE' })}
                  type="button"
                >
                  가짜
                </button>
              </div>
              <textarea
                aria-describedby={`statement-${index}-count`}
                maxLength={maxLength}
                onChange={(event) => updateStatement(index, { content: event.target.value })}
                placeholder={index === 0 ? '예: 나는 혼자 제주도 한 바퀴를 걸은 적이 있다.' : '나에 관한 흥미로운 이야기를 적어보세요.'}
                rows={3}
                value={statement.content}
              />
              <span className={count > 0 && count < minLength ? 'character-count is-invalid' : 'character-count'} id={`statement-${index}-count`}>
                {count} / {maxLength}
              </span>
            </fieldset>
          )
        })}
      </div>

      {errors.length ? (
        <InlineError>
          {errors.map((error) => <span key={error}>{error}</span>)}
        </InlineError>
      ) : null}
      {serverError ? <InlineError>{serverError}</InlineError> : null}

      <button className="button button--primary button--block" disabled={busy} type="submit">
        {busy ? '문장을 안전하게 저장하는 중…' : initialStatements ? '수정한 문장 저장하기' : '문장 제출하고 준비 완료'}
      </button>
    </form>
  )
}
