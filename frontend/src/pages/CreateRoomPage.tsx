import { useEffect, useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import { AppShell, StepLabel } from '../components/AppShell'
import { InlineError } from '../components/Feedback'
import { ArrowRightIcon } from '../components/Icons'
import { ApiError, roomApi, ttfGameApi } from '../lib/api'
import type { TtfTopic } from '../domain/types'

export function CreateRoomPage() {
  const navigate = useNavigate()
  const [name, setName] = useState('')
  const [maxParticipants, setMaxParticipants] = useState(30)
  const [statementMaxLength, setStatementMaxLength] = useState(100)
  const [votingDuration, setVotingDuration] = useState(60)
  const [speakerOrder, setSpeakerOrder] = useState<'RANDOM' | 'JOIN_ORDER'>('RANDOM')
  const [showVoters, setShowVoters] = useState(false)
  const [roundCount, setRoundCount] = useState(3)
  const [topics, setTopics] = useState<TtfTopic[]>([])
  const [selectedTopicIds, setSelectedTopicIds] = useState<string[]>([])
  const [randomTopics, setRandomTopics] = useState(false)
  const [topicsLoading, setTopicsLoading] = useState(true)
  const [topicsError, setTopicsError] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  const retryTopics = () => {
    setTopicsLoading(true)
    setTopicsError('')
    void ttfGameApi.getTopics()
      .then((catalog) => {
        setTopics(catalog)
        setSelectedTopicIds(catalog.slice(0, roundCount).map((topic) => topic.id))
      })
      .catch((caught) => {
        if (caught instanceof DOMException && caught.name === 'AbortError') return
        setTopicsError('주제 목록을 불러오지 못했어요. 다시 시도해 주세요.')
      })
      .finally(() => setTopicsLoading(false))
  }

  useEffect(() => {
    const controller = new AbortController()
    void ttfGameApi.getTopics(controller.signal)
      .then((catalog) => {
        setTopics(catalog)
        setSelectedTopicIds(catalog.slice(0, 3).map((topic) => topic.id))
      })
      .catch((caught) => {
        if (caught instanceof DOMException && caught.name === 'AbortError') return
        setTopicsError('주제 목록을 불러오지 못했어요. 다시 시도해 주세요.')
      })
      .finally(() => {
        if (!controller.signal.aborted) setTopicsLoading(false)
      })
    return () => controller.abort()
  }, [])

  const changeRoundCount = (nextCount: number) => {
    setRoundCount(nextCount)
    if (!Number.isInteger(nextCount) || nextCount < 1 || nextCount > topics.length) return
    setSelectedTopicIds((current) => {
      const retained = current.filter((id) => topics.some((topic) => topic.id === id)).slice(0, nextCount)
      const additions = topics
        .filter((topic) => !retained.includes(topic.id))
        .slice(0, nextCount - retained.length)
        .map((topic) => topic.id)
      return [...retained, ...additions]
    })
  }

  const toggleTopic = (topicId: string) => {
    setSelectedTopicIds((current) => {
      if (current.includes(topicId)) return current.filter((id) => id !== topicId)
      return current.length < roundCount ? [...current, topicId] : current
    })
  }

  const canRandomizeTopics = !busy && !topicsLoading && !topicsError
    && Number.isInteger(roundCount) && roundCount >= 1 && roundCount <= topics.length

  const pickRandomTopics = () => {
    const shuffled = [...topics]
    for (let index = shuffled.length - 1; index > 0; index -= 1) {
      const randomIndex = Math.floor(Math.random() * (index + 1))
      ;[shuffled[index], shuffled[randomIndex]] = [shuffled[randomIndex], shuffled[index]]
    }
    const selectedIds = new Set(shuffled.slice(0, roundCount).map((topic) => topic.id))
    return topics.filter((topic) => selectedIds.has(topic.id)).map((topic) => topic.id)
  }

  const create = async (event: FormEvent) => {
    event.preventDefault()
    setError('')
    if (!name.trim() || Array.from(name.trim()).length > 40) {
      setError('게임방 이름을 1~40자로 입력해 주세요.')
      return
    }
    if (!Number.isInteger(maxParticipants) || maxParticipants < 2 || maxParticipants > 100) {
      setError('최대 인원은 2~100명으로 설정해 주세요.')
      return
    }
    if (!Number.isInteger(votingDuration) || votingDuration < 15 || votingDuration > 180) {
      setError('투표 시간은 15~180초로 설정해 주세요.')
      return
    }
    if (!Number.isInteger(statementMaxLength) || statementMaxLength < 20 || statementMaxLength > 200) {
      setError('문장 최대 길이는 20~200자로 설정해 주세요.')
      return
    }
    if (!Number.isInteger(roundCount) || roundCount < 1 || roundCount > topics.length) {
      setError(`라운드 수는 1~${topics.length || 8}개로 설정해 주세요.`)
      return
    }
    if (!randomTopics && selectedTopicIds.length !== roundCount) {
      setError(`라운드 주제를 ${roundCount}개 선택해 주세요.`)
      return
    }
    setBusy(true)
    try {
      const created = await roomApi.create({
        name: name.trim(),
        settings: {
          max_participants: maxParticipants,
        },
        game: {
          type: 'TTF',
          settings: {
            statement_max_length: statementMaxLength,
            voting_duration_seconds: votingDuration,
            speaker_order: speakerOrder,
            anonymous_voting: !showVoters,
            round_count: roundCount,
            topic_ids: randomTopics ? pickRandomTopics() : selectedTopicIds,
          },
        },
      })
      if (created.game.type !== 'TTF') throw new Error('지원하지 않는 게임 유형이에요.')
      navigate(`/host/${created.game.id}`, { replace: true })
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : '방을 만들지 못했어요. 잠시 후 다시 시도해 주세요.')
    } finally {
      setBusy(false)
    }
  }

  return (
    <AppShell compact>
      <section className="form-page">
        <StepLabel>새 게임 준비</StepLabel>
        <h1>어떤 게임을<br />시작해 볼까요?</h1>
        <p className="page-description">나중에 바꿀 수 없는 설정이 있으니 시작 전에 한 번 확인해 주세요.</p>

        <form className="create-form card" onSubmit={(event) => void create(event)} noValidate>
          <div className="field-group">
            <label htmlFor="room-name">게임방 이름</label>
            <input
              autoFocus
              id="room-name"
              maxLength={40}
              onChange={(event) => setName(event.target.value)}
              placeholder="예: 마케팅팀 금요 워크숍"
              value={name}
            />
            <small>{Array.from(name).length} / 40</small>
          </div>

          <div className="field-row">
            <div className="field-group">
              <label htmlFor="max-participants">최대 인원</label>
              <div className="input-suffix"><input id="max-participants" max={100} min={2} onChange={(event) => setMaxParticipants(event.target.valueAsNumber)} required type="number" value={maxParticipants} /><span>명</span></div>
            </div>
            <div className="field-group">
              <label htmlFor="voting-duration">투표 시간</label>
              <div className="input-suffix"><input id="voting-duration" max={180} min={15} onChange={(event) => setVotingDuration(event.target.valueAsNumber)} required step={15} type="number" value={votingDuration} /><span>초</span></div>
            </div>
          </div>

          <div className="field-row">
            <div className="field-group">
              <label htmlFor="statement-length">문장 최대 길이</label>
              <div className="input-suffix"><input id="statement-length" max={200} min={20} onChange={(event) => setStatementMaxLength(event.target.valueAsNumber)} required step={10} type="number" value={statementMaxLength} /><span>자</span></div>
            </div>
            <div className="field-group">
              <label htmlFor="round-count">주제 라운드 수</label>
              <div className="input-suffix"><input id="round-count" max={topics.length || 8} min={1} onChange={(event) => changeRoundCount(event.target.valueAsNumber)} required type="number" value={roundCount} /><span>개</span></div>
            </div>
          </div>

          <fieldset className="topic-options">
            <legend>라운드 주제</legend>
            <p>참가자 모두가 선택된 주제마다 세 문장을 준비해요.</p>
            <button className="button button--secondary" disabled={randomTopics ? busy : !canRandomizeTopics} onClick={() => setRandomTopics((current) => !current)} type="button">
              {randomTopics ? '주제 직접 선택' : '주제 랜덤 선택'}
            </button>
            {randomTopics ? (
              <p role="status">랜덤 선택 중이에요. 방을 만들 때 라운드 수만큼 주제를 뽑아요. 어떤 주제인지는 방을 만든 뒤 확인해 주세요.</p>
            ) : (
              <p>{selectedTopicIds.length} / {roundCount}개 선택 · 랜덤 선택을 하면 방을 만들기 전에는 어떤 주제인지 알 수 없어요.</p>
            )}
            {topicsLoading ? <p className="topic-options__state" role="status">주제를 불러오는 중…</p> : null}
            {topicsError ? (
              <div className="topic-options__state">
                <span>{topicsError}</span>
                <button className="text-button" onClick={retryTopics} type="button">다시 불러오기</button>
              </div>
            ) : null}
            {!randomTopics && topics.length ? (
              <div className="topic-grid">
                {topics.map((topic) => {
                  const selected = selectedTopicIds.includes(topic.id)
                  const selectionFull = selectedTopicIds.length >= roundCount
                  return (
                    <label className="topic-card" key={topic.id}>
                      <input
                        checked={selected}
                        disabled={!selected && selectionFull}
                        onChange={() => toggleTopic(topic.id)}
                        type="checkbox"
                      />
                      <span><strong>{topic.title}</strong><small>{topic.example}</small></span>
                    </label>
                  )
                })}
              </div>
            ) : null}
          </fieldset>

          <fieldset className="option-group">
            <legend>발표 순서</legend>
            <label className="radio-card"><input checked={speakerOrder === 'RANDOM'} name="order" onChange={() => setSpeakerOrder('RANDOM')} type="radio" /><span><strong>무작위 순서</strong><small>누가 나올지 몰라 더 재미있어요</small></span></label>
            <label className="radio-card"><input checked={speakerOrder === 'JOIN_ORDER'} name="order" onChange={() => setSpeakerOrder('JOIN_ORDER')} type="radio" /><span><strong>입장 순서</strong><small>먼저 들어온 사람부터 시작해요</small></span></label>
          </fieldset>

          <label className="switch-row">
            <span><strong>투표자 공개</strong><small>결과에서 각 문장 아래에 투표한 사람을 배지로 보여줘요</small></span>
            <input checked={showVoters} onChange={(event) => setShowVoters(event.target.checked)} role="switch" type="checkbox" />
          </label>

          {error ? <InlineError>{error}</InlineError> : null}
          <button className="button button--primary button--block" disabled={busy || topicsLoading || Boolean(topicsError)} type="submit">
            {busy ? '게임방 만드는 중…' : <>게임방 만들기 <ArrowRightIcon /></>}
          </button>
        </form>
      </section>
    </AppShell>
  )
}
