import { useEffect, useState } from 'react'
import QRCode from 'qrcode'
import { CheckIcon, CopyIcon } from './Icons'

export function QrCode({ value, code }: { value: string; code: string }) {
  const [source, setSource] = useState('')
  const [copied, setCopied] = useState(false)

  useEffect(() => {
    let active = true
    void QRCode.toDataURL(value, {
      width: 360,
      margin: 2,
      color: { dark: '#18271f', light: '#ffffff' },
      errorCorrectionLevel: 'M',
    }).then((next) => {
      if (active) setSource(next)
    }).catch(() => {
      if (active) setSource('')
    })
    return () => {
      active = false
    }
  }, [value])

  const copyCode = async () => {
    try {
      await navigator.clipboard.writeText(code)
      setCopied(true)
      window.setTimeout(() => setCopied(false), 1_500)
    } catch {
      setCopied(false)
    }
  }

  return (
    <div className="qr-card">
      <div className="qr-card__image">
        {source ? <img alt={`방 코드 ${code} 참가 QR 코드`} src={source} /> : <span className="loader" />}
      </div>
      <div className="qr-card__copy">
        <p>휴대폰 카메라로 스캔하세요</p>
        <button className="room-code" onClick={() => void copyCode()} type="button" aria-label={`방 코드 ${code} 복사`}>
          <strong>{code}</strong>
          <span>{copied ? <><CheckIcon /> 복사됨</> : <><CopyIcon /> 코드 복사</>}</span>
        </button>
      </div>
    </div>
  )
}
