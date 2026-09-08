export type TtfGameStatus =
  | 'LOBBY'
  | 'SUBMISSION'
  | 'READY'
  | 'ROUND_INTRO'
  | 'VOTING'
  | 'VOTE_CLOSED'
  | 'RESULT'
  | 'PAUSED'
  | 'FINISHED'
  | 'CANCELLED'

export type RoomStatus = 'OPEN' | 'IN_GAME' | 'CLOSED' | 'EXPIRED'
export type GameType = string

export type Audience = 'participant' | 'host' | 'display'
export type ViewerRole = 'PARTICIPANT' | 'HOST' | 'DISPLAY'
export type StatementTruth = 'TRUE' | 'FAKE'

export interface TtfTopic {
  id: string
  title: string
  example: string
}

export interface RoomSettings {
  max_participants: number
}

export interface TtfGameSettings {
  statement_min_length: number
  statement_max_length: number
  voting_duration_seconds: number
  speaker_order: 'RANDOM' | 'JOIN_ORDER'
  anonymous_voting: boolean
  round_count: number
  topics: TtfTopic[]
}

export interface RoomSummary {
  id: string
  code: string
  name: string
  status: RoomStatus
  joinable: boolean
  participant_count: number
  settings: RoomSettings
  active_game: GameReference
}

export interface GameReference {
  id: string
  type: GameType
}

export interface ParticipantSummary {
  id: string
  nickname: string
  connection_status: 'ONLINE' | 'OFFLINE'
  is_ready: boolean
  score: number
  is_current_speaker: boolean
}

export interface Viewer {
  role: ViewerRole
  participant_id?: string
  nickname?: string
  is_ready?: boolean
}

export interface VisibleStatement {
  id: string
  content: string
  display_order: number
}

export interface VoteProgress {
  completed: number
  eligible: number
}

export interface StatementResult extends VisibleStatement {
  is_fake: boolean
  vote_count: number
  vote_rate: number
  voters?: Array<{ id: string; nickname: string }>
}

export interface ScoreChange {
  participant_id: string
  nickname: string
  delta: number
  total: number
}

export interface RoundResult {
  fake_statement_id: string
  statements: StatementResult[]
  correct_voter_count: number
  fooled_participant_count: number
  score_changes: ScoreChange[]
}

export interface RoundSnapshot {
  id: string
  number: number
  total: number
  topic: TtfTopic
  speaker: { id: string; nickname: string }
  statements: VisibleStatement[]
  voting_started_at?: string
  voting_ends_at?: string
  result_reveals_at?: string
  vote_progress: VoteProgress
  my_vote_statement_id?: string
  result?: RoundResult
}

export interface LeaderboardEntry {
  participant_id: string
  nickname: string
  score: number
  rank: number
  is_me: boolean
}

export interface TtfGameSnapshot {
  version: number
  server_time: string
  client_received_at_ms: number
  room: RoomSummary
  game: {
    id: string
    type: 'TTF'
    status: TtfGameStatus
    ready_count: number
    round_count: number
    current_round_number?: number
    paused_from_status?: TtfGameStatus
    settings: TtfGameSettings
  }
  viewer: Viewer
  participants?: ParticipantSummary[]
  my_statement_sets?: MyStatementSet[]
  current_round?: RoundSnapshot
  leaderboard?: LeaderboardEntry[]
}

export interface CreateRoomInput {
  name: string
  settings: {
    max_participants: number
  }
  game: {
    type: 'TTF'
    settings: {
      statement_max_length: number
      voting_duration_seconds: number
      speaker_order: TtfGameSettings['speaker_order']
      anonymous_voting: boolean
      round_count: number
      topic_ids: string[]
    }
  }
}

export interface CreateRoomResponse {
  room: {
    id: string
    code: string
    join_url: string
  }
  game: GameReference
}

export interface JoinRoomResponse {
  room_id: string
  participant_id: string
  game: GameReference
}

export interface StatementDraft {
  content: string
  truth: StatementTruth
}

export interface StatementSetDraft {
  topic_id: string
  statements: StatementDraft[]
}

export interface MyStatementSet {
  topic: TtfTopic
  statements: Array<{
    id: string
    content: string
    is_fake: boolean
  }>
}
