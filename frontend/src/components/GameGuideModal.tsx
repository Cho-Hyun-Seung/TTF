import { useEffect, useRef } from 'react'
import { CheckIcon, CloseIcon } from './Icons'

interface GameGuideModalProps {
  onClose: () => void
  open: boolean
}

const steps = [
  {
    title: '주제마다 세 문장 준비하기',
    description: '진행자가 고른 주제마다 진짜 이야기 두 개와 그럴듯한 가짜 하나를 적어요.',
  },
  {
    title: '이야기 들어보기',
    description: '발표자의 세 문장을 함께 보며 어떤 이야기가 수상한지 살펴봐요.',
  },
  {
    title: '가짜에 투표하기',
    description: '가짜라고 생각하는 문장 하나를 골라요. 마감 전에는 바꿀 수 있어요.',
  },
  {
    title: '정답과 점수 확인하기',
    description: '가짜 문장을 맞히면 1점! 모든 라운드가 끝나면 최종 순위가 공개돼요.',
  },
]

export function GameGuideModal({ onClose, open }: GameGuideModalProps) {
  const closeButtonRef = useRef<HTMLButtonElement>(null)
  const dialogRef = useRef<HTMLElement>(null)

  useEffect(() => {
    if (!open) return

    const previouslyFocusedElement = document.activeElement as HTMLElement | null
    const previousBodyOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    closeButtonRef.current?.focus()

    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.preventDefault()
        onClose()
        return
      }

      if (event.key !== 'Tab') return

      const focusableElements = dialogRef.current?.querySelectorAll<HTMLElement>(
        'button:not([disabled]), a[href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])',
      )

      if (!focusableElements?.length) return

      const firstElement = focusableElements[0]
      const lastElement = focusableElements[focusableElements.length - 1]

      if (event.shiftKey && document.activeElement === firstElement) {
        event.preventDefault()
        lastElement.focus()
      } else if (!event.shiftKey && document.activeElement === lastElement) {
        event.preventDefault()
        firstElement.focus()
      }
    }

    document.addEventListener('keydown', handleKeyDown)

    return () => {
      document.removeEventListener('keydown', handleKeyDown)
      document.body.style.overflow = previousBodyOverflow
      previouslyFocusedElement?.focus()
    }
  }, [onClose, open])

  if (!open) return null

  return (
    <div
      className="modal-backdrop"
      onMouseDown={(event) => {
        if (event.target === event.currentTarget) onClose()
      }}
    >
      <section
        aria-describedby="game-guide-description"
        aria-labelledby="game-guide-title"
        aria-modal="true"
        className="game-guide-modal"
        ref={dialogRef}
        role="dialog"
      >
        <button
          aria-label="게임 설명 닫기"
          className="modal-close"
          onClick={onClose}
          ref={closeButtonRef}
          type="button"
        >
          <CloseIcon />
        </button>

        <header className="game-guide-modal__header">
          <div className="mini-card-deck" aria-hidden="true">
            <span>TRUE</span><span>TRUE</span><span>FAKE?</span>
          </div>
          <div>
            <p className="eyebrow">처음이어도 금방 배워요</p>
            <h2 id="game-guide-title">진짜 둘, 가짜 하나</h2>
            <p id="game-guide-description">
              서로의 의외의 모습을 발견하는 가벼운 추리 게임이에요.
            </p>
          </div>
        </header>

        <ol className="game-guide-steps">
          {steps.map((step, index) => (
            <li key={step.title}>
              <span>{String(index + 1).padStart(2, '0')}</span>
              <div>
                <h3>{step.title}</h3>
                <p>{step.description}</p>
              </div>
            </li>
          ))}
        </ol>

        <div className="game-guide-rules">
          <h3>이것만 기억하세요</h3>
          <ul>
            <li><CheckIcon /> 발표자는 자기 차례에 투표하지 않아요.</li>
            <li><CheckIcon /> 가짜를 맞힌 사람은 1점을 얻어요.</li>
            <li><CheckIcon /> 발표자는 속인 사람 한 명마다 1점을 얻어요.</li>
            <li><CheckIcon /> 같은 점수라면 함께 같은 순위가 돼요.</li>
          </ul>
        </div>

        <button className="button button--primary button--block" onClick={onClose} type="button">
          알겠어요
        </button>
      </section>
    </div>
  )
}
