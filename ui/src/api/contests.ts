export interface Greeting {
  text?: string
  use: boolean
}

export interface Contest {
  id?: number
  name: string
  year: number
  country: string
  images?: string
  campaign?: string
  monumentIdTemplate?: string
  greeting?: Greeting
}

async function request<T>(url: string, init?: RequestInit): Promise<T> {
  const res = await fetch(url, init)
  if (!res.ok) throw new Error(`HTTP ${res.status}`)
  return res.json()
}

async function requestVoid(url: string, init?: RequestInit): Promise<void> {
  const res = await fetch(url, init)
  if (!res.ok) throw new Error(`HTTP ${res.status}`)
}

export function listContests(): Promise<Contest[]> {
  return request('/api/contests')
}

export function createContest(contest: Contest): Promise<Contest> {
  return request('/api/contests', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(contest)
  })
}

export function updateContest(contest: Contest): Promise<void> {
  return requestVoid('/api/contests', {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(contest)
  })
}
