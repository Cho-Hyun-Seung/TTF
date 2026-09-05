import { useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import { AppShell, StepLabel } from '../components/AppShell'
import { InlineError } from '../components/Feedback'
import { ArrowRightIcon } from '../components/Icons'
import { ApiError, roomApi } from '../lib/api'

export function CreateRoomPage() {
  const navigate = useNavigate()
  const [name, setName] = useState('')
  const [maxParticipants, setMaxParticipants] = useState(30)
  const [statementMaxLength, setStatementMaxLength] = useState(100)
  const [votingDuration, setVotingDuration] = useState(60)
  const [speakerOrder, setSpeakerOrder] = useState<'RANDOM' | 'JOIN_ORDER'>('RANDOM')
  const [anonymousVoting, setAnonymousVoting] = useState(true)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

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
            anonymous_voting: anonymousVoting,
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

          <div className="field-group">
            <label htmlFor="statement-length">문장 최대 길이</label>
            <div className="input-suffix"><input id="statement-length" max={200} min={20} onChange={(event) => setStatementMaxLength(event.target.valueAsNumber)} required step={10} type="number" value={statementMaxLength} /><span>자</span></div>
          </div>

          <fieldset className="option-group">
            <legend>발표 순서</legend>
            <label className="radio-card"><input checked={speakerOrder === 'RANDOM'} name="order" onChange={() => setSpeakerOrder('RANDOM')} type="radio" /><span><strong>무작위 순서</strong><small>누가 나올지 몰라 더 재미있어요</small></span></label>
            <label className="radio-card"><input checked={speakerOrder === 'JOIN_ORDER'} name="order" onChange={() => setSpeakerOrder('JOIN_ORDER')} type="radio" /><span><strong>입장 순서</strong><small>먼저 들어온 사람부터 시작해요</small></span></label>
          </fieldset>

          <label className="switch-row">
            <span><strong>익명 투표</strong><small>결과에 누가 무엇을 골랐는지 숨겨요</small></span>
            <input checked={anonymousVoting} onChange={(event) => setAnonymousVoting(event.target.checked)} role="switch" type="checkbox" />
          </label>

          {error ? <InlineError>{error}</InlineError> : null}
          <button className="button button--primary button--block" disabled={busy} type="submit">
            {busy ? '게임방 만드는 중…' : <>게임방 만들기 <ArrowRightIcon /></>}
          </button>
        </form>
      </section>
    </AppShell>
  )
}
