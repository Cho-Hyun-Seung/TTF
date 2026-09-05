import { useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { AppShell } from '../components/AppShell'
import { ArrowRightIcon, CheckIcon, UsersIcon } from '../components/Icons'
import { normalizeRoomCode, ROOM_CODE_PATTERN } from '../domain/validation'

export function HomePage() {
  const [code, setCode] = useState('')
  const [showError, setShowError] = useState(false)
  const navigate = useNavigate()

  const join = (event: FormEvent) => {
    event.preventDefault()
    if (!ROOM_CODE_PATTERN.test(code)) {
      setShowError(true)
      return
    }
    navigate(`/join/${code}`)
  }

  return (
    <AppShell>
      <section className="hero">
        <div className="hero__copy">
          <p className="eyebrow">QR로 바로 시작하는 아이스브레이킹</p>
          <h1>우리 사이,<br /><em>세 문장</em>이면 충분해요.</h1>
          <p className="hero__description">
            진짜 이야기 둘과 그럴듯한 가짜 하나.<br />누가 가장 감쪽같이 모두를 속일까요?
          </p>
          <div className="hero__actions">
            <Link className="button button--primary" to="/rooms/new">
              게임방 만들기 <ArrowRightIcon />
            </Link>
            <span>회원가입도, 앱 설치도 필요 없어요</span>
          </div>
        </div>

        <div className="hero-art" aria-hidden="true">
          <span className="hero-art__orbit hero-art__orbit--one" />
          <span className="hero-art__orbit hero-art__orbit--two" />
          <article className="story-card story-card--one"><span>01</span><p>나는 사막에서<br />밤을 보낸 적이 있다</p><i>TRUE?</i></article>
          <article className="story-card story-card--two"><span>02</span><p>나는 한 번도<br />커피를 마신 적이 없다</p><i>TRUE?</i></article>
          <article className="story-card story-card--three"><span>03</span><p>나는 세 개의<br />악기를 연주할 수 있다</p><i>FAKE?</i></article>
        </div>
      </section>

      <section className="join-strip" aria-labelledby="join-title">
        <div className="join-strip__heading">
          <span className="icon-box"><UsersIcon /></span>
          <div><p>이미 방이 있나요?</p><h2 id="join-title">방 코드로 바로 참여하세요</h2></div>
        </div>
        <form className="code-form" onSubmit={join} noValidate>
          <label className="sr-only" htmlFor="room-code">6자리 방 코드</label>
          <input
            aria-describedby={showError ? 'room-code-error' : undefined}
            aria-invalid={showError}
            autoCapitalize="characters"
            autoComplete="off"
            id="room-code"
            inputMode="text"
            maxLength={6}
            onChange={(event) => {
              setCode(normalizeRoomCode(event.target.value))
              setShowError(false)
            }}
            placeholder="6자리 코드"
            value={code}
          />
          <button className="button button--dark" type="submit">참여하기 <ArrowRightIcon /></button>
          {showError ? <p className="field-error" id="room-code-error">영문·숫자 6자리 코드를 입력해 주세요.</p> : null}
        </form>
      </section>

      <section className="how-it-works" aria-labelledby="how-title">
        <div className="section-heading section-heading--center">
          <p className="eyebrow">3분이면 준비 끝</p>
          <h2 id="how-title">게임은 이렇게 시작해요</h2>
        </div>
        <ol className="step-grid">
          <li><span>01</span><div><h3>QR로 모이기</h3><p>진행자가 띄운 QR을 스캔하고 닉네임만 입력해요.</p></div></li>
          <li><span>02</span><div><h3>세 문장 적기</h3><p>나에 관한 진짜 둘과 그럴듯한 가짜 하나를 적어요.</p></div></li>
          <li><span>03</span><div><h3>가짜 맞히기</h3><p>서로의 이야기를 듣고 가짜라고 생각하는 문장에 투표해요.</p></div></li>
        </ol>
        <p className="feature-note"><CheckIcon /> 최대 100명까지 함께할 수 있어요</p>
      </section>
    </AppShell>
  )
}

