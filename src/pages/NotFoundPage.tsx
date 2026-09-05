import { Link } from 'react-router-dom'
import { AppShell } from '../components/AppShell'
import { ErrorView } from '../components/Feedback'

export function NotFoundPage() {
  return (
    <AppShell compact>
      <ErrorView
        title="페이지를 찾을 수 없어요"
        message="주소가 잘못되었거나 이동된 페이지예요."
        action={<Link className="button button--secondary" to="/">홈으로 돌아가기</Link>}
      />
    </AppShell>
  )
}

