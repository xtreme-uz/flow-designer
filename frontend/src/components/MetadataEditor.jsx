import { useState, useEffect } from 'react';
import './MetadataEditor.css';

/**
 * Modal for viewing and editing flow metadata.
 * Receives metadata extracted from flowType (see App.jsx currentMetadata).
 * onSave returns an object that gets merged into currentDeploymentData.flowType.
 */
export default function MetadataEditor({ metadata, onSave, onClose }) {
  const [formData, setFormData] = useState({
    description: '',
    version: '1.0',
    component: 'THUB'
  });

  useEffect(() => {
    if (metadata) {
      setFormData({
        description: metadata.description || '',
        version: metadata.version || '1.0',
        component: metadata.component || 'THUB'
      });
    }
  }, [metadata]);

  const handleChange = (field, value) => {
    setFormData(prev => ({ ...prev, [field]: value }));
  };

  const handleSubmit = (e) => {
    e.preventDefault();
    // Return flowType-compatible fields to merge
    onSave({
      description: formData.description,
      version: formData.version,
      component: formData.component
    });
  };

  const formatDate = (dateStr) => {
    if (!dateStr) return null;
    try {
      return new Date(dateStr).toLocaleString();
    } catch {
      return dateStr;
    }
  };

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-content" onClick={(e) => e.stopPropagation()}>
        <div className="modal-header">
          <h3>Flow Metadata</h3>
          <button className="close-btn" onClick={onClose}>✕</button>
        </div>
        <form onSubmit={handleSubmit}>
          <div className="modal-body">
            <div className="form-group">
              <label htmlFor="meta-description">Description</label>
              <textarea
                id="meta-description"
                value={formData.description}
                onChange={(e) => handleChange('description', e.target.value)}
                placeholder="Brief description of this flow"
                rows={3}
              />
            </div>

            <div className="metadata-form-row">
              <div className="form-group">
                <label htmlFor="meta-version">Version</label>
                <input
                  id="meta-version"
                  type="text"
                  value={formData.version}
                  onChange={(e) => handleChange('version', e.target.value)}
                  placeholder="e.g., 1.0"
                />
              </div>
              <div className="form-group">
                <label htmlFor="meta-component">Component</label>
                <input
                  id="meta-component"
                  type="text"
                  value={formData.component}
                  onChange={(e) => handleChange('component', e.target.value)}
                  placeholder="e.g., THUB"
                />
              </div>
            </div>

            {(metadata?.createdBy || metadata?.lastModifiedBy) && (
              <div className="metadata-info-section">
                <div className="metadata-info-title">Audit Info</div>
                {metadata.createdBy && (
                  <div className="metadata-info-row">
                    <span className="metadata-info-label">Created by</span>
                    <span className="metadata-info-value">
                      {metadata.createdBy}
                      {metadata.createdAt && (
                        <span className="metadata-info-date"> — {formatDate(metadata.createdAt)}</span>
                      )}
                    </span>
                  </div>
                )}
                {metadata.lastModifiedBy && (
                  <div className="metadata-info-row">
                    <span className="metadata-info-label">Modified by</span>
                    <span className="metadata-info-value">
                      {metadata.lastModifiedBy}
                      {metadata.lastModifiedAt && (
                        <span className="metadata-info-date"> — {formatDate(metadata.lastModifiedAt)}</span>
                      )}
                    </span>
                  </div>
                )}
              </div>
            )}
          </div>
          <div className="form-actions">
            <button type="submit" className="btn btn-primary">
              Save
            </button>
            <button type="button" className="btn btn-secondary" onClick={onClose}>
              Cancel
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
