/**
 * LINE 用戶管理頁面
 * - 分頁查詢用戶（暱稱關鍵字、狀態、標籤篩選）
 * - 單筆指派標籤
 * - 批量貼 / 移除標籤（透過表格選取多筆）
 */
import { useCallback, useEffect, useState } from 'react'
import {
  Avatar,
  Button,
  Input,
  Modal,
  Select,
  Space,
  Table,
  Tag as AntTag,
  Tooltip,
  Typography,
  message,
} from 'antd'
import { CopyOutlined, NotificationOutlined, TagsOutlined } from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import type { ColumnsType, TablePaginationConfig } from 'antd/es/table'
import { assignTags, bulkTag, getLineUsers } from '../api/lineUsers'
import { getTags } from '../api/tags'
import TagChip from '../components/Tags/TagChip'
import TagPicker from '../components/Tags/TagPicker'
import type { BulkTagAction, LineUser, LineUserStatus, Tag } from '../types'

const { Title } = Typography

const PAGE_SIZE = 20

export default function LineUsers() {
  const navigate = useNavigate()
  const [data, setData] = useState<LineUser[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(1)
  const [loading, setLoading] = useState(false)
  const [tags, setTags] = useState<Tag[]>([])

  // 篩選
  const [keyword, setKeyword] = useState('')
  const [statusFilter, setStatusFilter] = useState<LineUserStatus | undefined>()
  const [tagFilter, setTagFilter] = useState<number[]>([])

  // 多選
  const [selectedRowKeys, setSelectedRowKeys] = useState<React.Key[]>([])

  // Modal：單筆指派標籤
  const [assignOpen, setAssignOpen] = useState(false)
  const [assignTarget, setAssignTarget] = useState<LineUser | null>(null)
  const [assignTagIds, setAssignTagIds] = useState<number[]>([])

  // Modal：批量
  const [bulkOpen, setBulkOpen] = useState(false)
  const [bulkAction, setBulkAction] = useState<BulkTagAction>('ADD')
  const [bulkTagIds, setBulkTagIds] = useState<number[]>([])

  const fetchTags = useCallback(async () => {
    try {
      const res = await getTags()
      setTags(res.data.data)
    } catch {
      // ignore
    }
  }, [])

  const fetchData = useCallback(async () => {
    setLoading(true)
    try {
      const res = await getLineUsers({
        keyword: keyword || undefined,
        status: statusFilter,
        tagIds: tagFilter.length ? tagFilter : undefined,
        page: page - 1,
        size: PAGE_SIZE,
      })
      const p = res.data.data
      setData(p.content)
      setTotal(p.totalElements)
    } catch (err: unknown) {
      message.error(err instanceof Error ? err.message : '載入用戶失敗')
    } finally {
      setLoading(false)
    }
  }, [keyword, statusFilter, tagFilter, page])

  useEffect(() => { fetchTags() }, [fetchTags])
  useEffect(() => { fetchData() }, [fetchData])

  const handleOpenAssign = (user: LineUser) => {
    setAssignTarget(user)
    setAssignTagIds(user.tags.map((t) => t.id))
    setAssignOpen(true)
  }

  const handleAssign = async () => {
    if (!assignTarget) return
    try {
      await assignTags(assignTarget.id, assignTagIds)
      message.success('標籤已更新')
      setAssignOpen(false)
      fetchData()
      fetchTags()
    } catch (err: unknown) {
      message.error(err instanceof Error ? err.message : '操作失敗')
    }
  }

  const handleBulk = async () => {
    if (!selectedRowKeys.length || !bulkTagIds.length) {
      message.warning('請選擇用戶與標籤')
      return
    }
    try {
      const res = await bulkTag({
        userIds: selectedRowKeys.map((k) => Number(k)),
        tagIds: bulkTagIds,
        action: bulkAction,
      })
      message.success(`已影響 ${res.data.data.affected} 筆`)
      setBulkOpen(false)
      setSelectedRowKeys([])
      setBulkTagIds([])
      fetchData()
      fetchTags()
    } catch (err: unknown) {
      message.error(err instanceof Error ? err.message : '操作失敗')
    }
  }

  const columns: ColumnsType<LineUser> = [
    {
      title: '頭像',
      dataIndex: 'pictureUrl',
      width: 70,
      render: (url, record) => <Avatar src={url}>{(record.displayName ?? '?').charAt(0)}</Avatar>,
    },
    {
      title: '暱稱',
      dataIndex: 'displayName',
      render: (v) => v ?? <span style={{ color: '#999' }}>未取得</span>,
    },
    {
      title: 'LINE userId',
      dataIndex: 'lineUserId',
      width: 180,
      render: (id: string) => <LineUserIdCell lineUserId={id} />,
    },
    {
      title: '狀態',
      dataIndex: 'status',
      width: 100,
      render: (s: LineUserStatus) =>
        s === 'FOLLOWED' ? <AntTag color="green">已加好友</AntTag> : <AntTag>已封鎖</AntTag>,
    },
    {
      title: '標籤',
      dataIndex: 'tags',
      render: (tags: Tag[]) =>
        tags.length ? tags.map((t) => <TagChip key={t.id} tag={t} />) : <span style={{ color: '#bbb' }}>—</span>,
    },
    {
      title: '加入時間',
      dataIndex: 'followedAt',
      width: 180,
      render: (v) => (v ? new Date(v).toLocaleString('zh-TW') : '—'),
    },
    {
      title: '操作',
      width: 120,
      render: (_, record) => (
        <Button size="small" icon={<TagsOutlined />} onClick={() => handleOpenAssign(record)}>
          標籤
        </Button>
      ),
    },
  ]

  const pagination: TablePaginationConfig = {
    current: page,
    pageSize: PAGE_SIZE,
    total,
    showSizeChanger: false,
    onChange: setPage,
  }

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 16 }}>
        <Title level={4} style={{ margin: 0 }}>LINE 用戶</Title>
        <Space>
          <Button
            icon={<NotificationOutlined />}
            disabled={!selectedRowKeys.length}
            onClick={() => {
              const selectedUsers = data.filter((u) => selectedRowKeys.includes(u.id))
              navigate('/broadcasts/new', {
                state: {
                  userIds: selectedUsers.map((u) => u.id),
                  users: selectedUsers,
                },
              })
            }}
          >
            對所選人推播（{selectedRowKeys.length}）
          </Button>
          <Button
            type="primary"
            icon={<TagsOutlined />}
            disabled={!selectedRowKeys.length}
            onClick={() => setBulkOpen(true)}
          >
            批量貼標籤（已選 {selectedRowKeys.length}）
          </Button>
        </Space>
      </div>

      <Space style={{ marginBottom: 16 }} wrap>
        <Input.Search
          placeholder="搜尋暱稱"
          allowClear
          style={{ width: 240 }}
          onSearch={(v) => { setKeyword(v); setPage(1) }}
        />
        <Select
          placeholder="狀態"
          allowClear
          style={{ width: 140 }}
          value={statusFilter}
          onChange={(v) => { setStatusFilter(v); setPage(1) }}
          options={[
            { value: 'FOLLOWED', label: '已加好友' },
            { value: 'BLOCKED', label: '已封鎖' },
          ]}
        />
        <TagPicker
          tags={tags}
          value={tagFilter}
          onChange={(v) => { setTagFilter(v); setPage(1) }}
          placeholder="依標籤篩選"
          style={{ width: 240 }}
        />
      </Space>

      <Table
        rowKey="id"
        columns={columns}
        dataSource={data}
        loading={loading}
        pagination={pagination}
        rowSelection={{
          selectedRowKeys,
          onChange: setSelectedRowKeys,
        }}
      />

      {/* 單筆指派標籤 */}
      <Modal
        title={`指派標籤：${assignTarget?.displayName ?? assignTarget?.lineUserId ?? ''}`}
        open={assignOpen}
        onOk={handleAssign}
        onCancel={() => setAssignOpen(false)}
        okText="儲存"
        cancelText="取消"
        destroyOnClose
      >
        <p style={{ color: '#888', marginBottom: 8 }}>覆寫式：以下方選取為準</p>
        <TagPicker tags={tags} value={assignTagIds} onChange={setAssignTagIds} style={{ width: '100%' }} />
      </Modal>

      {/* 批量操作 */}
      <Modal
        title={`批量操作（${selectedRowKeys.length} 位用戶）`}
        open={bulkOpen}
        onOk={handleBulk}
        onCancel={() => setBulkOpen(false)}
        okText="執行"
        cancelText="取消"
      >
        <Space direction="vertical" style={{ width: '100%' }}>
          <Select
            value={bulkAction}
            onChange={setBulkAction}
            style={{ width: '100%' }}
            options={[
              { value: 'ADD', label: '新增標籤' },
              { value: 'REMOVE', label: '移除標籤' },
            ]}
          />
          <TagPicker tags={tags} value={bulkTagIds} onChange={setBulkTagIds} style={{ width: '100%' }} />
        </Space>
      </Modal>
    </div>
  )
}

/**
 * LINE userId 顯示 + 複製按鈕。
 * userId 太長（U + 32 hex）故截短顯示前 6 碼 + …，hover 看完整、點按鈕複製到剪貼簿。
 */
function LineUserIdCell({ lineUserId }: { lineUserId: string }) {
  const short =
    lineUserId.length > 6 ? `${lineUserId.slice(0, 6)}…` : lineUserId

  const handleCopy = async () => {
    try {
      await navigator.clipboard.writeText(lineUserId)
      message.success('已複製 LINE userId')
    } catch {
      message.error('複製失敗，瀏覽器不支援或被禁止')
    }
  }

  return (
    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}>
      <Tooltip title={lineUserId}>
        <code style={{ fontSize: 12 }}>{short}</code>
      </Tooltip>
      <Button
        type="text"
        size="small"
        icon={<CopyOutlined />}
        onClick={handleCopy}
        title="複製完整 LINE userId"
      />
    </span>
  )
}
