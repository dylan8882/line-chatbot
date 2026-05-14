/**
 * 新增/編輯問答對的 Modal 表單
 * 使用 React Hook Form + Zod 進行表單驗證
 */
import { useEffect } from 'react'
import { Modal, Form, Input, Select, InputNumber, Switch, Button, Space } from 'antd'
import { useForm, Controller } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import type { QAPair, MatchType } from '../../types'

const schema = z.object({
  keyword: z.string().min(1, '關鍵字不能為空').max(500, '關鍵字最多 500 字'),
  answer: z.string().min(1, '回答內容不能為空'),
  matchType: z.enum(['EXACT', 'CONTAINS', 'REGEX'] as const),
  priority: z.number().int().min(0).max(9999),
  isActive: z.boolean(),
})

type FormValues = z.infer<typeof schema>

interface QAFormProps {
  open: boolean
  editingItem: QAPair | null
  onSubmit: (values: FormValues) => Promise<void>
  onCancel: () => void
  loading: boolean
}

const matchTypeOptions: { label: string; value: MatchType }[] = [
  { label: '完全符合 (EXACT)', value: 'EXACT' },
  { label: '包含關鍵字 (CONTAINS)', value: 'CONTAINS' },
  { label: '正規表達式 (REGEX)', value: 'REGEX' },
]

export default function QAForm({ open, editingItem, onSubmit, onCancel, loading }: QAFormProps) {
  const {
    control,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<FormValues>({
    resolver: zodResolver(schema),
    defaultValues: {
      keyword: '',
      answer: '',
      matchType: 'CONTAINS',
      priority: 0,
      isActive: true,
    },
  })

  useEffect(() => {
    if (open) {
      if (editingItem) {
        reset({
          keyword: editingItem.keyword,
          answer: editingItem.answer,
          matchType: editingItem.matchType,
          priority: editingItem.priority,
          isActive: editingItem.isActive,
        })
      } else {
        reset({ keyword: '', answer: '', matchType: 'CONTAINS', priority: 0, isActive: true })
      }
    }
  }, [open, editingItem, reset])

  return (
    <Modal
      title={editingItem ? '編輯問答對' : '新增問答對'}
      open={open}
      onCancel={onCancel}
      footer={null}
      destroyOnClose
    >
      <form onSubmit={handleSubmit(onSubmit)} style={{ marginTop: 16 }}>
        <Form.Item
          label="關鍵字"
          validateStatus={errors.keyword ? 'error' : ''}
          help={errors.keyword?.message}
          required
        >
          <Controller
            name="keyword"
            control={control}
            render={({ field }) => <Input {...field} placeholder="輸入觸發關鍵字" />}
          />
        </Form.Item>

        <Form.Item
          label="回答內容"
          validateStatus={errors.answer ? 'error' : ''}
          help={errors.answer?.message}
          required
        >
          <Controller
            name="answer"
            control={control}
            render={({ field }) => (
              <Input.TextArea {...field} rows={4} placeholder="輸入回答內容" />
            )}
          />
        </Form.Item>

        <Form.Item label="比對方式">
          <Controller
            name="matchType"
            control={control}
            render={({ field }) => (
              <Select {...field} options={matchTypeOptions} style={{ width: '100%' }} />
            )}
          />
        </Form.Item>

        <Form.Item
          label="優先順序"
          validateStatus={errors.priority ? 'error' : ''}
          help={errors.priority?.message}
        >
          <Controller
            name="priority"
            control={control}
            render={({ field }) => (
              <InputNumber
                {...field}
                min={0}
                max={9999}
                style={{ width: '100%' }}
                placeholder="數字越大優先順序越高"
              />
            )}
          />
        </Form.Item>

        <Form.Item label="啟用">
          <Controller
            name="isActive"
            control={control}
            render={({ field }) => (
              <Switch checked={field.value} onChange={field.onChange} />
            )}
          />
        </Form.Item>

        <Form.Item style={{ marginBottom: 0, textAlign: 'right' }}>
          <Space>
            <Button onClick={onCancel}>取消</Button>
            <Button type="primary" htmlType="submit" loading={loading}>
              {editingItem ? '儲存' : '新增'}
            </Button>
          </Space>
        </Form.Item>
      </form>
    </Modal>
  )
}
