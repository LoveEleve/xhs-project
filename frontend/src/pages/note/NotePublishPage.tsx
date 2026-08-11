import { useState, useEffect } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { Card, Form, Input, Button, Radio, Upload, Space, Tag, message, Spin } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import type { UploadFile } from 'antd';
import { publishNote, saveDraft, updateNote, getNoteRawDetail, uploadImage } from '../../api/note';
import type { PublishNoteRequest } from '../../types';

export default function NotePublishPage() {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const editId = searchParams.get('id');

  const [form] = Form.useForm<PublishNoteRequest>();
  const [noteType, setNoteType] = useState<number>(0);
  const [images, setImages] = useState<string[]>([]);
  const [tags, setTags] = useState<string[]>([]);
  const [tagInput, setTagInput] = useState('');
  const [fileList, setFileList] = useState<UploadFile[]>([]);
  const [loading, setLoading] = useState(!!editId);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (!editId) return;
    getNoteRawDetail(Number(editId))
      .then((resp) => {
        const n = resp.data.data;
        form.setFieldsValue({
          title: n.title,
          content: n.content,
          noteType: n.noteType,
          videoUrl: n.videoUrl,
          tags: n.tags,
        });
        setNoteType(n.noteType);
        setImages(n.images || []);
        setTags(n.tags || []);
        setLoading(false);
      })
      .catch((e) => { message.error(e.response?.data?.message || '加载笔记失败'); setLoading(false); });
  }, [editId]);

  const handleUpload = async (file: File): Promise<string> => {
    const fd = new FormData();
    fd.append('file', file);
    const resp = await uploadImage(fd);
    return resp.data.data.url;
  };

  const submit = async (draft: boolean) => {
    try {
      await form.validateFields();
    } catch { message.error('请填写必填项'); return; }
    const values = form.getFieldsValue();
    const payload: PublishNoteRequest = {
      title: values.title,
      content: values.content,
      noteType,
      images,
      tags,
      coverUrl: images[0],
      videoUrl: values.videoUrl,
    };
    setSubmitting(true);
    try {
      if (editId) {
        await updateNote(Number(editId), payload);
        message.success('已保存');
      } else if (draft) {
        await saveDraft(payload);
        message.success('草稿已保存');
      } else {
        await publishNote(payload);
        message.success('发布成功');
      }
      navigate('/me/notes');
    } catch (e: any) {
      message.error(e.response?.data?.message || (draft ? '保存草稿失败' : '发布失败'));
    } finally {
      setSubmitting(false);
    }
  };

  if (loading) return <div style={{ textAlign: 'center', padding: 80 }}><Spin size="large" /></div>;

  return (
    <div style={{ maxWidth: 760, margin: '0 auto' }}>
      <Card title={editId ? '编辑笔记' : '发布笔记'}>
        <Form form={form} layout="vertical">
          <Form.Item name="title" label="标题" rules={[{ required: true, message: '请输入标题' }, { max: 128, message: '标题不超过128字' }]}>
            <Input placeholder="标题" />
          </Form.Item>

          <Form.Item label="类型">
            <Radio.Group value={noteType} onChange={(e) => setNoteType(e.target.value)}>
              <Radio value={0}>图文</Radio>
              <Radio value={1}>视频</Radio>
            </Radio.Group>
          </Form.Item>

          {noteType === 1 && (
            <Form.Item name="videoUrl" label="视频地址" rules={[{ required: true, message: '请输入视频地址' }]}>
              <Input placeholder="视频 URL" />
            </Form.Item>
          )}

          <Form.Item label="图片（最多9张）">
            <Upload
              listType="picture-card"
              fileList={fileList}
              accept="image/*"
              customRequest={async ({ file, onSuccess, onError }) => {
                try {
                  const url = await handleUpload(file as File);
                  setImages(prev => [...prev, url]);
                  onSuccess?.({ url });
                } catch (e: any) {
                  message.error(e.response?.data?.message || '上传失败');
                  onError?.(e as Error);
                }
              }}
              onChange={({ fileList: fl }) => setFileList(fl)}
              onRemove={(file) => {
                const url = (file.response as any)?.url;
                if (url) setImages(prev => prev.filter(u => u !== url));
              }}
            >
              {images.length >= 9 ? null : (
                <div><PlusOutlined /><div style={{ marginTop: 4 }}>上传</div></div>
              )}
            </Upload>
          </Form.Item>

          <Form.Item name="content" label="正文" rules={[{ required: true, message: '请输入正文' }]}>
            <Input.TextArea rows={8} placeholder="正文" />
          </Form.Item>

          <Form.Item label="标签">
            <div>
              <Input
                value={tagInput}
                placeholder="输入标签后回车"
                onPressEnter={(e) => {
                  const v = (e.target as HTMLInputElement).value.trim();
                  if (v && !tags.includes(v)) setTags(prev => [...prev, v]);
                  setTagInput('');
                }}
                style={{ width: 300 }}
              />
              <div style={{ marginTop: 8 }}>
                {tags.map(t => (
                  <Tag key={t} closable onClose={() => setTags(prev => prev.filter(x => x !== t))}>#{t}</Tag>
                ))}
              </div>
            </div>
          </Form.Item>
        </Form>

        <Space style={{ float: 'right' }}>
          {!editId && (
            <Button loading={submitting} onClick={() => submit(true)}>存草稿</Button>
          )}
          <Button type="primary" loading={submitting} onClick={() => submit(false)}>
            {editId ? '保存' : '发布'}
          </Button>
        </Space>
      </Card>
    </div>
  );
}
