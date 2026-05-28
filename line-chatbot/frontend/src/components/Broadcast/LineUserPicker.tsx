/**
 * LINE 用戶多選選擇器（給推播 USER_LIST 目標用）
 *
 * 行為：
 * - 輸入暱稱或 lineUserId 觸發 server-side 搜尋（debounce 300ms）
 * - 已選的用戶會被快取，即使從搜尋結果消失也能繼續顯示 label
 * - 可預載 initialUsers（例如從 LineUsers 頁帶過來的用戶物件）
 */
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Avatar, Select, Spin, Tag } from 'antd'
import { getLineUsers } from '../../api/lineUsers'
import type { LineUser } from '../../types'

interface Props {
  value?: number[]
  onChange?: (value: number[]) => void
  /** 預載的用戶（例如從別頁帶過來的選擇），會優先放入快取以顯示 label */
  initialUsers?: LineUser[]
  placeholder?: string
  /** 只搜已加好友 / 全部，預設只搜已加好友（USER_LIST 推播一定要在好友列表內） */
  followedOnly?: boolean
}

const DEBOUNCE_MS = 300

export default function LineUserPicker({
  value,
  onChange,
  initialUsers,
  placeholder = '輸入暱稱或 LINE userId 搜尋',
  followedOnly = true,
}: Props) {
  // 已知用戶快取：(id → LineUser)；同時涵蓋初始預載 + 歷次搜尋結果中被選到的人
  const [cache, setCache] = useState<Map<number, LineUser>>(() => {
    const m = new Map<number, LineUser>()
    initialUsers?.forEach((u) => m.set(u.id, u))
    return m
  })
  const [searchResults, setSearchResults] = useState<LineUser[]>([])
  const [loading, setLoading] = useState(false)
  const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  // 預載 initialUsers 變動時同步進 cache
  useEffect(() => {
    if (!initialUsers || initialUsers.length === 0) return
    setCache((prev) => {
      const next = new Map(prev)
      initialUsers.forEach((u) => next.set(u.id, u))
      return next
    })
  }, [initialUsers])

  const doSearch = useCallback(
    async (keyword: string) => {
      setLoading(true)
      try {
        const res = await getLineUsers({
          keyword: keyword || undefined,
          status: followedOnly ? 'FOLLOWED' : undefined,
          size: 30,
        })
        setSearchResults(res.data.data.content)
      } catch {
        setSearchResults([])
      } finally {
        setLoading(false)
      }
    },
    [followedOnly],
  )

  const handleSearch = (keyword: string) => {
    if (debounceRef.current) clearTimeout(debounceRef.current)
    debounceRef.current = setTimeout(() => doSearch(keyword), DEBOUNCE_MS)
  }

  // 首次 focus 預載幾筆，讓使用者一打開就看到候選
  const handleFocus = () => {
    if (searchResults.length === 0) doSearch('')
  }

  const handleChange = (vals: number[]) => {
    // 新增選擇的人從搜尋結果寫入快取（之後 label 才不會掉）
    setCache((prev) => {
      const next = new Map(prev)
      vals.forEach((id) => {
        if (!next.has(id)) {
          const u = searchResults.find((x) => x.id === id)
          if (u) next.set(id, u)
        }
      })
      return next
    })
    onChange?.(vals)
  }

  // options = (快取所有) ∪ (本次搜尋結果)，搜尋結果優先用最新版本
  const options = useMemo(() => {
    const merged = new Map<number, LineUser>()
    cache.forEach((u, id) => merged.set(id, u))
    searchResults.forEach((u) => merged.set(u.id, u))
    return Array.from(merged.values()).map((u) => ({
      value: u.id,
      label: <UserOption user={u} />,
      // 也提供純文字 label 給 Antd 在選中後顯示
      textLabel: u.displayName ?? u.lineUserId,
    }))
  }, [cache, searchResults])

  return (
    <Select
      mode="multiple"
      value={value}
      onChange={handleChange}
      onSearch={handleSearch}
      onFocus={handleFocus}
      filterOption={false}
      placeholder={placeholder}
      style={{ width: '100%' }}
      loading={loading}
      notFoundContent={loading ? <Spin size="small" /> : null}
      options={options}
      // 自訂選中後 tag 上的顯示
      optionLabelProp="textLabel"
      maxTagCount="responsive"
    />
  )
}

function UserOption({ user }: { user: LineUser }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
      <Avatar size={20} src={user.pictureUrl}>
        {(user.displayName ?? '?').charAt(0)}
      </Avatar>
      <span style={{ flex: 1, minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis' }}>
        {user.displayName ?? <span style={{ color: '#999' }}>未取得暱稱</span>}
      </span>
      <span style={{ color: '#888', fontSize: 11, fontFamily: 'monospace' }}>
        {user.lineUserId.length > 12
          ? `${user.lineUserId.slice(0, 6)}…${user.lineUserId.slice(-4)}`
          : user.lineUserId}
      </span>
      {user.status === 'BLOCKED' && (
        <Tag color="default" style={{ marginInlineEnd: 0 }}>
          已封鎖
        </Tag>
      )}
    </div>
  )
}
