import { useState, useEffect } from 'react';
import './EdgeEditor.css';

/**
 * Available result types for edge transitions
 */
const RESULT_TYPES = [
  { value: 'success', label: 'Success', description: 'Action completed successfully' },
  { value: 'business-error', label: 'Business Error', description: 'Business validation failed' },
  { value: 'technical-error', label: 'Technical Error', description: 'Technical failure occurred' },
  { value: 'technical-expiration', label: 'Technical Expiration', description: 'Timeout expired' },
  { value: 'unpredictable-status', label: 'Unpredictable Status', description: 'Unknown status, needs check' },
  { value: 'special-status', label: 'Special Status', description: 'Special handling required' },
];

/**
 * Side panel for editing edge properties
 */
export default function EdgeEditor({ edge, onUpdate, onDelete, onClose }) {
  const [formData, setFormData] = useState({
    label: 'success',
    actionResultTypeIds: 'success',
    storeAsRequestResult: true
  });

  useEffect(() => {
    if (edge) {
      setFormData({
        label: edge.label || edge.data?.actionResultTypeIds || 'success',
        actionResultTypeIds: edge.data?.actionResultTypeIds || edge.label || 'success',
        storeAsRequestResult: edge.data?.storeAsRequestResult ?? true
      });
    }
  }, [edge]);

  const handleResultTypeChange = (value, checked) => {
    setFormData(prev => {
      const current = prev.actionResultTypeIds ? prev.actionResultTypeIds.split(',') : [];
      let updated;
      if (checked) {
        updated = [...current, value];
      } else {
        updated = current.filter(v => v !== value);
      }
      if (updated.length === 0) return prev;
      const joined = updated.join(',');
      return { ...prev, label: joined, actionResultTypeIds: joined };
    });
  };

  const handleStoreResultChange = (e) => {
    setFormData(prev => ({
      ...prev,
      storeAsRequestResult: e.target.checked
    }));
  };

  const handleSubmit = (e) => {
    e.preventDefault();
    onUpdate(edge.id, formData);
  };

  const handleDelete = () => {
    if (confirm('Are you sure you want to delete this edge?')) {
      onDelete(edge.id);
    }
  };

  if (!edge) return null;

  return (
    <aside className="edge-editor">
      <div className="editor-header">
        <h3>Edit Transition</h3>
        <button className="close-btn" onClick={onClose}>✕</button>
      </div>

      <form onSubmit={handleSubmit}>
        <div className="edge-info">
          <div className="info-row">
            <span className="info-label">From:</span>
            <span className="info-value">{edge.source}</span>
          </div>
          <div className="info-row">
            <span className="info-label">To:</span>
            <span className="info-value">{edge.target}</span>
          </div>
        </div>

        <div className="form-group">
          <label>Result Types *</label>
          <div className="result-type-options">
            {RESULT_TYPES.map((type) => {
              const selected = formData.actionResultTypeIds?.split(',').includes(type.value);
              return (
                <label
                  key={type.value}
                  className={`result-type-option ${selected ? 'selected' : ''}`}
                >
                  <input
                    type="checkbox"
                    value={type.value}
                    checked={selected}
                    onChange={(e) => handleResultTypeChange(type.value, e.target.checked)}
                  />
                  <div className="option-content">
                    <span className="option-label">{type.label}</span>
                    <span className="option-description">{type.description}</span>
                  </div>
                </label>
              );
            })}
          </div>
        </div>

        <div className="form-group">
          <label className="checkbox-label">
            <input
              type="checkbox"
              checked={formData.storeAsRequestResult}
              onChange={handleStoreResultChange}
            />
            <span>Store as Request Result</span>
          </label>
          <small>Save this transition result for the payment request</small>
        </div>

        <div className="form-actions">
          <button type="submit" className="btn btn-primary">
            Save Changes
          </button>
          <button type="button" className="btn btn-secondary" onClick={onClose}>
            Cancel
          </button>
        </div>

        <div className="danger-zone">
          <button type="button" className="btn btn-danger" onClick={handleDelete}>
            Delete Transition
          </button>
        </div>
      </form>
    </aside>
  );
}
