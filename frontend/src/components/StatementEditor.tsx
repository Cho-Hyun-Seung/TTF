import { useMemo, useState, type FormEvent } from 'react'
import type { StatementDraft, StatementSetDraft, TtfTopic } from '../domain/types'
import { characterCount, validateStatements } from '../domain/validation'
import { ApiError, ttfGameApi } from '../lib/api'
import { InlineError } from './Feedback'
import { CheckIcon } from './Icons'

const emptyStatements = (): StatementDraft[] => [
  { content: '', truth: 'TRUE' },
  { content: '', truth: 'TRUE' },
  { content: '', truth: 'FAKE' },
]

export function StatementEditor({
  gameId,
  minLength,
  maxLength,
  topics,
  initialStatementSets,
  onSaved,
}: {
  gameId: string
  minLength: number
  maxLength: number
  topics: TtfTopic[]
  initialStatementSets?: StatementSetDraft[]
  onSaved: () => Promise<void> | void
}) {
  const [statementSets, setStatementSets] = useState<StatementSetDraft[]>(() => topics.map((topic) => ({
    topic_id: topic.id,
    statements: initialStatementSets?.find((set) => set.topic_id === topic.id)?.statements ?? emptyStatements(),
  })))
  const [activeTopicId, setActiveTopicId] = useState(topics[0]?.id ?? '')
  const [submitted, setSubmitted] = useState(false)
  const [busy, setBusy] = useState(false)
  const [serverError, setServerError] = useState('')
  const validationByTopic = useMemo(() => {
    const validation = new Map(statementSets.map((set) => [
      set.topic_id,
      validateStatements(set.statements, minLength, maxLength),
    ]))
    const firstTopicByContent = new Map<string, string>()
    const duplicateTopicIds = new Set<string>()
    statementSets.forEach((set) => set.statements.forEach((statement) => {
      const normalized = statement.content.trim().replace(/\s+/g, ' ').toLocaleLowerCase('ko-KR')
      if (!normalized) return
      const firstTopicId = firstTopicByContent.get(normalized)
      if (firstTopicId && firstTopicId !== set.topic_id) {
        duplicateTopicIds.add(firstTopicId)
        duplicateTopicIds.add(set.topic_id)
      } else {
        firstTopicByContent.set(normalized, set.topic_id)
      }
    }))
    duplicateTopicIds.forEach((topicId) => {
      validation.set(topicId, [
        ...(validation.get(topicId) ?? []),
        '다른 주제와 같은 문장은 사용할 수 없어요.',
      ])
    })
    return validation
  }, [maxLength, minLength, statementSets])
  const errors = submitted
    ? statementSets.flatMap((set) => validationByTopic.get(set.topic_id)?.map((error) => {
        const title = topics.find((topic) => topic.id === set.topic_id)?.title ?? set.topic_id
        return `${title}: ${error}`
      }) ?? [])
    : []
  const activeIndex = Math.max(0, topics.findIndex((topic) => topic.id === activeTopicId))
  const activeTopic = topics[activeIndex]
  const activeSet = statementSets.find((set) => set.topic_id === activeTopic?.id)

  const updateStatement = (topicId: string, index: number, patch: Partial<StatementDraft>) => {
    setStatementSets((current) => current.map((set) => set.topic_id === topicId
      ? {
          ...set,
          statements: set.statements.map((statement, itemIndex) =>
            itemIndex === index ? { ...statement, ...patch } : statement),
        }
      : set))
  }

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    setSubmitted(true)
    setServerError('')
    const firstInvalidSet = statementSets.find((set) => validationByTopic.get(set.topic_id)?.length)
    if (firstInvalidSet) {
      setActiveTopicId(firstInvalidSet.topic_id)
      return
    }

    setBusy(true)
    try {
      await ttfGameApi.saveStatements(gameId, statementSets)
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
        <h2>주제마다 진짜 둘, 가짜 하나를 적어주세요</h2>
        <p>선택된 {topics.length}개 주제를 모두 작성하면 준비가 끝나요.</p>
      </div>

      <div className="privacy-note">
        이름, 연락처처럼 민감한 개인정보는 문장에 적지 마세요.
      </div>

      <div className="topic-tabs" aria-label="라운드 주제" role="tablist">
        {topics.map((topic, index) => {
          const complete = validationByTopic.get(topic.id)?.length === 0
          return (
            <button
              aria-controls={`topic-panel-${topic.id}`}
              aria-selected={topic.id === activeTopic?.id}
              className={topic.id === activeTopic?.id ? 'is-active' : ''}
              id={`topic-tab-${topic.id}`}
              key={topic.id}
              onClick={() => setActiveTopicId(topic.id)}
              role="tab"
              type="button"
            >
              <span>{index + 1}</span> {topic.title} {complete ? <CheckIcon /> : null}
            </button>
          )
        })}
      </div>

      {activeTopic && activeSet ? (
        <section
          aria-labelledby={`topic-tab-${activeTopic.id}`}
          className="topic-editor"
          id={`topic-panel-${activeTopic.id}`}
          role="tabpanel"
        >
          <div className="topic-prompt">
            <span>이번 주제</span>
            <h3>{activeTopic.title}</h3>
            <p><strong>예시</strong> {activeTopic.example}</p>
            <small>예시는 참고만 하고 나만의 이야기로 적어주세요.</small>
          </div>

          <div className="statement-fields">
            {activeSet.statements.map((statement, index) => {
              const count = characterCount(statement.content)
              return (
                <fieldset className="statement-field" key={index}>
                  <legend>문장 {index + 1}</legend>
                  <div className="truth-toggle" aria-label={`문장 ${index + 1} 진위`}>
                    <button
                      aria-pressed={statement.truth === 'TRUE'}
                      className={statement.truth === 'TRUE' ? 'is-active' : ''}
                      onClick={() => updateStatement(activeTopic.id, index, { truth: 'TRUE' })}
                      type="button"
                    >
                      진짜
                    </button>
                    <button
                      aria-pressed={statement.truth === 'FAKE'}
                      className={statement.truth === 'FAKE' ? 'is-active is-fake' : ''}
                      onClick={() => updateStatement(activeTopic.id, index, { truth: 'FAKE' })}
                      type="button"
                    >
                      가짜
                    </button>
                  </div>
                  <textarea
                    aria-label={`${activeTopic.title} 문장 ${index + 1}`}
                    aria-describedby={`statement-${activeTopic.id}-${index}-count`}
                    maxLength={maxLength}
                    onChange={(event) => updateStatement(activeTopic.id, index, { content: event.target.value })}
                    placeholder={index === 0 ? activeTopic.example : '이 주제에 맞는 나의 이야기를 적어보세요.'}
                    rows={3}
                    value={statement.content}
                  />
                  <span className={count > 0 && count < minLength ? 'character-count is-invalid' : 'character-count'} id={`statement-${activeTopic.id}-${index}-count`}>
                    {count} / {maxLength}
                  </span>
                </fieldset>
              )
            })}
          </div>

          <div className="topic-editor__navigation">
            <button
              className="button button--secondary"
              disabled={activeIndex === 0}
              onClick={() => setActiveTopicId(topics[activeIndex - 1]?.id ?? activeTopic.id)}
              type="button"
            >
              이전 주제
            </button>
            <span>{activeIndex + 1} / {topics.length}</span>
            <button
              className="button button--secondary"
              disabled={activeIndex === topics.length - 1}
              onClick={() => setActiveTopicId(topics[activeIndex + 1]?.id ?? activeTopic.id)}
              type="button"
            >
              다음 주제
            </button>
          </div>
        </section>
      ) : null}

      {errors.length ? (
        <InlineError>
          {errors.map((error) => <span key={error}>{error}</span>)}
        </InlineError>
      ) : null}
      {serverError ? <InlineError>{serverError}</InlineError> : null}

      <button className="button button--primary button--block" disabled={busy} type="submit">
        {busy ? '문장을 안전하게 저장하는 중…' : initialStatementSets ? '수정한 문장 저장하기' : '모든 문장 제출하고 준비 완료'}
      </button>
    </form>
  )
}
