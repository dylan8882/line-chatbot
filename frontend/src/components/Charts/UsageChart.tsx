/**
 * 訊息量趨勢折線圖元件
 * 使用 Recharts 繪製每日 QA 命中、AI 回覆與總量趨勢
 */
import {
  LineChart,
  Line,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  Legend,
  ResponsiveContainer,
} from 'recharts'
import type { DailyStats } from '../../types'

interface UsageChartProps {
  data: DailyStats[]
}

export default function UsageChart({ data }: UsageChartProps) {
  return (
    <ResponsiveContainer width="100%" height={300}>
      <LineChart data={data} margin={{ top: 5, right: 30, left: 0, bottom: 5 }}>
        <CartesianGrid strokeDasharray="3 3" />
        <XAxis dataKey="date" tick={{ fontSize: 12 }} />
        <YAxis allowDecimals={false} />
        <Tooltip />
        <Legend />
        <Line
          type="monotone"
          dataKey="messageCount"
          name="總訊息數"
          stroke="#1890ff"
          strokeWidth={2}
          dot={false}
        />
        <Line
          type="monotone"
          dataKey="qaCount"
          name="QA 命中"
          stroke="#52c41a"
          strokeWidth={2}
          dot={false}
        />
        <Line
          type="monotone"
          dataKey="aiCount"
          name="AI 回覆"
          stroke="#fa8c16"
          strokeWidth={2}
          dot={false}
        />
      </LineChart>
    </ResponsiveContainer>
  )
}
