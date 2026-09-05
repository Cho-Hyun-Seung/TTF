import { useEffect, useState, type FormEvent } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { AppShell, StepLabel } from '../components/AppShell'
import { ErrorView, InlineError, LoadingView } from '../components/Feedback'
import { ArrowRightIcon, UsersIcon } from '../components/Icons'
import type { RoomSummary } from '../domain/types'
import { normalizeRoomCode, ROOM_CODE_PATTERN, validateNickname } from '../domain/validation'
import { ApiError, roomApi } from '../lib/api'

export function JoinRoomPage() {
  const params = useParams()
  const code = normalizeRoomCode(params.code ?? '')
  return <JoinRoomContent code={code} key={code} />
}

function JoinRoomContent({ code }: { code: string }) {
  const navigate = useNavigate()
  const [room, setRoom] = useState<RoomSummary | null>(null)
  const [nickname, setNickname] = useState('')
  const validCode = ROOM_CODE_PATTERN.test(code)
  const [loading, setLoading] = useState(validCode)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [loadError, setLoadError] = useState(validCode ? '' : '방 코드 형식이 올바르지 않아요.')

  useEffect(() => {
    if (!validCode) return
    const controller = new AbortController()
    void roomApi.getByCode(code, controller.signal)
      .then((nextRoom) => setRoom(nextRoom))
      .catch((caught: unknown) => {
        if (caught instanceof DOMException && caught.name === 'AbortError') return
        if (caught instanceof ApiError && (caught.code === 'ROOM_NOT_FOUND' || caught.code === 'ROOM_EXPIRED')) {
          setLoadError('존재하지 않거나 만료된 게임방이에요.')
        } else {
          setLoadError(caught instanceof ApiError ? caught.message : '게임방을 확인하지 못했어요.')
        }
      })
      .finally(() => setLoading(false))
    return () => controller.abort()
  }, [code, validCode])

  const join = async (event: FormEvent) => {
    event.preventDefault()
    if (!room) return
    const validationError = validateNickname(nickname)
    if (validationError) {
      setError(validationError)
      return
    }
    setBusy(true)
    setError('')
    try {
      const joined = await roomApi.join(room.id, nickname)
      if (joined.game.type !== 'TTF') throw new Error('지원하지 않는 게임 유형이에요.')
      navigate(`/play/${joined.game.id}`, { replace: true })
    } catch (caught) {
      if (caught instanceof ApiError) setError(caught.message)
      else setError('방에 입장하지 못했어요. 잠시 후 다시 시도해 주세요.')
    } finally {
      setBusy(false)
    }
  }

  if (loading) return <AppShell compact><LoadingView message="게임방을 확인하고 있어요" /></AppShell>
  if (loadError || !room) {
    return (
      <AppShell compact>
        <ErrorView
          message={loadError || '게임방을 찾지 못했어요.'}
          action={<Link className="button button--secondary" to="/">다른 코드 입력하기</Link>}
        />
      </AppShell>
    )
  }

  if (room.active_game.type !== 'TTF') {
    return (
      <AppShell compact>
        <ErrorView
          title="지원하지 않는 게임이에요"
          message="이 버전에서는 해당 게임에 참여할 수 없어요. 최신 버전으로 다시 시도해 주세요."
          action={<Link className="button button--secondary" to="/">홈으로 돌아가기</Link>}
        />
      </AppShell>
    )
  }

  if (!room.joinable) {
    return (
      <AppShell compact>
        <ErrorView
          title="이미 시작된 게임이에요"
          message="게임이 시작된 뒤에는 새로 입장할 수 없어요. 진행자에게 새 게임을 요청해 주세요."
          action={<Link className="button button--secondary" to="/">홈으로 돌아가기</Link>}
        />
      </AppShell>
    )
  }

  return (
    <AppShell compact>
      <section className="join-page">
        <StepLabel>방 코드 {room.code}</StepLabel>
        <div className="join-room-title">
          <span className="icon-box"><UsersIcon /></span>
          <div><h1>{room.name}</h1><p>현재 {room.participant_count}명 참여 중 · 최대 {room.settings.max_participants}명</p></div>
        </div>

        <form className="card join-form" onSubmit={(event) => void join(event)} noValidate>
          <div className="section-heading">
            <p className="eyebrow">반가워요!</p>
            <h2>어떤 이름으로 불러드릴까요?</h2>
            <p>이 방에서 다른 참가자에게 보일 이름이에요.</p>
          </div>
          <div className="field-group">
            <label htmlFor="nickname">닉네임</label>
            <input
              aria-invalid={Boolean(error)}
              autoComplete="nickname"
              autoFocus
              id="nickname"
              maxLength={20}
              onChange={(event) => {
                setNickname(event.target.value)
                setError('')
              }}
              placeholder="닉네임을 입력하세요"
              value={nickname}
            />
            <small>{Array.from(nickname).length} / 20</small>
          </div>
          {error ? <InlineError>{error}</InlineError> : null}
          <button className="button button--primary button--block" disabled={busy} type="submit">
            {busy ? '입장하는 중…' : <>입장하기 <ArrowRightIcon /></>}
          </button>
        </form>
      </section>
    </AppShell>
  )
}
