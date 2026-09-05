import { Route, Routes } from 'react-router-dom'
import { CreateRoomPage } from './pages/CreateRoomPage'
import { DisplayPage } from './pages/DisplayPage'
import { HomePage } from './pages/HomePage'
import { HostRoomPage } from './pages/HostRoomPage'
import { JoinRoomPage } from './pages/JoinRoomPage'
import { NotFoundPage } from './pages/NotFoundPage'
import { ParticipantRoomPage } from './pages/ParticipantRoomPage'

export default function App() {
  return (
    <Routes>
      <Route element={<HomePage />} path="/" />
      <Route element={<CreateRoomPage />} path="/rooms/new" />
      <Route element={<JoinRoomPage />} path="/join/:code" />
      <Route element={<ParticipantRoomPage />} path="/play/:gameId" />
      <Route element={<HostRoomPage />} path="/host/:gameId" />
      <Route element={<DisplayPage />} path="/display/:gameId" />
      <Route element={<NotFoundPage />} path="*" />
    </Routes>
  )
}

