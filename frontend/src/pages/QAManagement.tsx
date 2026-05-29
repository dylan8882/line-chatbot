/**
 * 問答管理頁面
 * 提供 QA 問答對的 CRUD 操作，支援分頁、啟用/停用切換
 */
import { useEffect, useState, useCallback } from 'react'
import { Button, Typography, message, Alert } from 'antd'
import { PlusOutlined } from '@ant-design/icons'
import { getQAList, createQA, updateQA, deleteQA, toggleQA } from '../api/qa'
import QATable from '../components/QA/QATable'
import QAForm from '../components/QA/QAForm'
import type { QAPair, MatchType } from '../types'

const { Title } = Typography

interface FormValues {
  keyword: string
  answer: string
  matchType: MatchType
  priority: number
  isActive: boolean
}

export default function QAManagement() {
  const [data, setData] = useState<QAPair[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(1)
  const [loading, setLoading] = useState(false)
  const [formLoading, setFormLoading] = useState(false)
  const [formOpen, setFormOpen] = useState(false)
  const [editingItem, setEditingItem] = useState<QAPair | null>(null)
  const [error, setError] = useState<string | null>(null)

  const PAGE_SIZE = 20

  const fetchData = useCallback(async (p: number) => {
    setLoading(true)
    setError(null)
    try {
      const res = await getQAList(p - 1, PAGE_SIZE)
      const pageData = res.data.data
      setData(pageData.content)
      setTotal(pageData.totalElements)
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : '載入資料失敗')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    fetchData(page)
  }, [page, fetchData])

  const handleOpenCreate = () => {
    setEditingItem(null)
    setFormOpen(true)
  }

  const handleOpenEdit = (item: QAPair) => {
    setEditingItem(item)
    setFormOpen(true)
  }

  const handleFormSubmit = async (values: FormValues) => {
    setFormLoading(true)
    try {
      if (editingItem) {
        await updateQA(editingItem.id, values)
        message.success('更新成功')
      } else {
        await createQA(values)
        message.success('新增成功')
      }
      setFormOpen(false)
      fetchData(page)
    } catch (err: unknown) {
      message.error(err instanceof Error ? err.message : '操作失敗')
    } finally {
      setFormLoading(false)
    }
  }

  const handleDelete = async (id: number) => {
    try {
      await deleteQA(id)
      message.success('刪除成功')
      fetchData(page)
    } catch (err: unknown) {
      message.error(err instanceof Error ? err.message : '刪除失敗')
    }
  }

  const handleToggle = async (id: number) => {
    try {
      await toggleQA(id)
      // 更新本地狀態，不重新拉取
      setData((prev) =>
        prev.map((item) =>
          item.id === id ? { ...item, isActive: !item.isActive } : item
        )
      )
    } catch (err: unknown) {
      message.error(err instanceof Error ? err.message : '切換失敗')
    }
  }

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
        <Title level={4} style={{ margin: 0 }}>
          問答管理
        </Title>
        <Button type="primary" icon={<PlusOutlined />} onClick={handleOpenCreate}>
          新增問答
        </Button>
      </div>

      {error && (
        <Alert message={error} type="error" showIcon style={{ marginBottom: 16 }} />
      )}

      <QATable
        data={data}
        total={total}
        page={page}
        pageSize={PAGE_SIZE}
        loading={loading}
        onPageChange={setPage}
        onEdit={handleOpenEdit}
        onDelete={handleDelete}
        onToggle={handleToggle}
      />

      <QAForm
        open={formOpen}
        editingItem={editingItem}
        onSubmit={handleFormSubmit}
        onCancel={() => setFormOpen(false)}
        loading={formLoading}
      />
    </div>
  )
}
